"""Public-fixture reconstruction experiments. No network, database, secrets or model calls."""
import argparse
import hashlib
import json
from pathlib import Path
import unicodedata

# Canonical LF digests derived from the already pinned original CRLF inputs.
# Only uniform CRLF -> LF is allowed; JSON formatting, BOM and all other bytes stay significant.
RECONSTRUCTION_SHA256 = "b84d772c444f7d0a77a8928323738aedcb1043004449ee9395cb40d8c2b36666"
PUBLIC_BUNDLE_SHA256 = "dea815e16d67ae71b0d84d452c71c8d229db689113fb281e7bd6b3606e448c3e"
CASES_SHA256 = "38231df8e3bae06b03e6082a347b052b399f4ca703025bcc82e5557237fe8609"
VARIANTS = {
    "baseline": (.25, 8, 24, False),
    "previous_neighbor_one": (.25, 8, 24, True),
    "threshold_020": (.20, 8, 24, False),
    "threshold_016": (.16, 8, 24, False),
    "threshold_020_top5": (.20, 5, 15, False),
    "candidate_limit8": (.25, 8, 8, False),
}


def normalize(text):
    return "".join(c for c in text.lower() if not c.isspace() and not unicodedata.category(c).startswith("P"))


def version_key(metadata):
    return tuple(metadata.get(k) for k in ("ent_code", "knowledge_base_id", "document_id", "document_version"))


def eligible(metadata, scope, model, ready_versions):
    return (all(metadata.get(k) is not None for k in ("ent_code", "knowledge_base_id", "document_id", "document_version", "chunk_id", "chunk_index", "source"))
            and all(str(metadata.get(k)).strip() for k in ("ent_code", "knowledge_base_id", "document_id", "chunk_id", "source"))
            and isinstance(metadata["document_version"], int) and metadata["document_version"] > 0
            and isinstance(metadata["chunk_index"], int) and metadata["chunk_index"] >= 0
            and (metadata["ent_code"], metadata["knowledge_base_id"]) == scope
            and metadata.get("embedding_model") == model and version_key(metadata) in ready_versions)


def select(scores, metadata, scope, model, ready_versions, threshold, top_k, candidate_limit):
    # Historical exact-cosine rows, not a new HNSW query. Scope is applied before limiting.
    rows = sorted((x for x in scores if x["cosine"] > threshold
                   and eligible(metadata[x["chunkId"]], scope, model, ready_versions)), key=lambda x: -x["cosine"])[:candidate_limit]
    first, rest, seen = [], [], set()
    for row in rows:
        doc = metadata[row["chunkId"]]["document_id"]
        (rest if doc in seen else first).append(row)
        seen.add(doc)
    return sorted((first + rest)[:top_k], key=lambda x: -x["cosine"])


def expand_previous(selected, chunks, scope, model, ready_versions):
    """At most one previous chunk, preserving all baseline anchors and their order."""
    present = {x["chunkId"] for x in selected}
    for anchor in selected:
        m = chunks[anchor["chunkId"]]["metadata"]
        matches = [c for c in chunks.values() if eligible(c["metadata"], scope, model, ready_versions)
                   and version_key(c["metadata"]) == version_key(m)
                   and c["metadata"]["source"] == m["source"]
                   and c["metadata"]["chunk_index"] == m["chunk_index"] - 1
                   and c["metadata"]["chunk_id"] not in present]
        # Ambiguous duplicate identities fail closed, never choose an arbitrary neighbor.
        if len(matches) == 1:
            c = matches[0]
            neighbor = {"source": c["metadata"]["source"], "chunkId": c["metadata"]["chunk_id"],
                        "chunkIndex": c["metadata"]["chunk_index"], "cosine": None, "text": c["text"],
                        "selectionReason": "previous_same_scope_ready_version_assumption"}
            index = selected.index(anchor)
            return selected[:index] + [neighbor] + selected[index:]
    return list(selected)


def context_text(selected):
    return "\n\n".join(f"[{i}] {x['source']}\n{x['text']}" for i, x in enumerate(selected, 1))


def canonical_lf_bytes(raw):
    without_crlf = raw.replace(b"\r\n", b"")
    if b"\r" in without_crlf or (b"\r\n" in raw and b"\n" in without_crlf):
        raise ValueError("Invalid line endings; expected uniform LF or CRLF")
    return raw.replace(b"\r\n", b"\n")


def checked_json(path, expected):
    raw = canonical_lf_bytes(path.read_bytes())
    allowed = (expected,) if isinstance(expected, str) else expected
    if hashlib.sha256(raw).hexdigest() not in allowed:
        raise ValueError("Input identity mismatch; public-fixture experiment stopped")
    return raw.decode("utf-8-sig")


def run(reconstruction, cases):
    chunks = {c["metadata"]["chunk_id"]: c for c in reconstruction["chunks"]}
    if reconstruction.get("schema") == "public-fixture-reconstruction-v1":
        # Compact portable bundle: text stored once, historic scope IDs replaced by fixture labels.
        for case in reconstruction["cases"]:
            for field in ["allScores", "reconstructedContext"]:
                case[field] = [dict(row, source=chunks[row["chunkId"]]["metadata"]["source"],
                                    chunkIndex=chunks[row["chunkId"]]["metadata"]["chunk_index"],
                                    text=chunks[row["chunkId"]]["text"]) for row in case[field]]
    metadata = {k: c["metadata"] for k, c in chunks.items()}
    primary = [c for c in chunks.values() if not c["metadata"]["source"].startswith("other-")]
    scopes = {(c["metadata"]["ent_code"], c["metadata"]["knowledge_base_id"]) for c in primary}
    models = {c["metadata"]["embedding_model"] for c in primary}
    if len(scopes) != 1 or len(models) != 1:
        raise ValueError("Ambiguous historical scope or model identity")
    scope, model = next(iter(scopes)), next(iter(models))
    ready = {version_key(c["metadata"]) for c in primary}  # Explicit historical assumption, NOT a DB check.
    if set(cases) != {c["caseId"] for c in reconstruction["cases"]}:
        raise ValueError("Dataset and reconstruction cases differ")
    runs, contexts = {}, {}
    for name, (threshold, top_k, cap, neighbor) in VARIANTS.items():
        rows = []
        for old in reconstruction["cases"]:
            case = cases[old["caseId"]]
            chosen = select(old["allScores"], metadata, scope, model, ready, threshold, top_k, cap)
            if name == "baseline" and [x["chunkId"] for x in chosen] != [x["chunkId"] for x in old["reconstructedContext"]]:
                raise ValueError("Baseline reconstruction mismatch")
            if neighbor:
                chosen = expand_previous(chosen, chunks, scope, model, ready)
            text = context_text(chosen)
            # Preserve same-document adjacency for the explicit subject/value boundary check.
            compact = normalize("".join(x["text"] for x in chosen if x["source"] == "product-manual.txt"))
            selected_docs = [x["source"].removesuffix(".txt") for x in chosen]
            expected = set(case["expectedDocumentIds"])
            hit_facts = [f for f in case.get("keyFacts", []) if normalize(f) in normalize(text)]
            contexts[f"{name}/{case['caseId']}"] = text
            rows.append({"caseId": case["caseId"], "answerable": case["answerable"],
                         "historicallyRefused": old["refused"], "chunks": [{k: v for k, v in x.items() if k != "text"} for x in chosen],
                         "evidenceUtf8Bytes": len(text.encode()), "literalFactHits": hit_facts,
                         "literalFactTotal": len(case.get("keyFacts", [])), "singleFileSubject500MB": "单文件上限为500mb" in compact,
                         "explicitUploadFormatsSentence": "文档上传支持pdfwordexceltxthtml和rtf" in normalize(text),
                         "recallAt5": len(set(selected_docs[:5]) & expected) / len(expected) if expected else 0,
                         "mrrAt5": next((1 / (i+1) for i, d in enumerate(selected_docs[:5]) if d in expected), 0),
                         "crossScopeSelected": sum(not eligible(metadata[x["chunkId"]], scope, model, ready) for x in chosen)})
        runs[name] = {"parameters": {"threshold": threshold, "topK": top_k, "candidateLimit": cap, "onePreviousNeighbor": neighbor}, "cases": rows}
    base = {c["caseId"]: c for c in runs["baseline"]["cases"]}
    for variant in runs.values():
        rows = variant["cases"]
        ans = [r for r in rows if r["answerable"]]
        variant["summary"] = {"documentRecallAt5": sum(r["recallAt5"] for r in ans) / len(ans),
                              "documentMrrAt5": sum(r["mrrAt5"] for r in ans) / len(ans),
                              "answerableLiteralFactsInEvidence": sum(len(r["literalFactHits"]) for r in ans),
                              "answerableLiteralFactsTotal": sum(r["literalFactTotal"] for r in ans),
                              "noAnswerWithNonemptyEvidence": sum(bool(r["chunks"]) for r in rows if not r["answerable"]),
                              "crossScopeSelected": sum(r["crossScopeSelected"] for r in rows),
                              "historicallyNonRefusedCasesLosingLiteralFacts": [r["caseId"] for r in ans if not r["historicallyRefused"]
                                  and set(base[r["caseId"]]["literalFactHits"]) - set(r["literalFactHits"])],
                              "noAnswerExtraChunks": {r["caseId"]:len(r["chunks"])-len(base[r["caseId"]]["chunks"]) for r in rows if not r["answerable"]}}
    return {"kind": "OFFLINE_RECONSTRUCTION_NOT_MODEL_QUALITY", "historicalReadyVersionsAssumed": True,
            "scoredInterferenceCandidatesAvailable": False, "variants": runs}, contexts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=Path("src/test/resources/rag-eval/diagnostics/reconstruction-v1.json"))
    parser.add_argument("--cases", type=Path, default=Path("src/test/resources/rag-eval/v1/cases.jsonl"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result, contexts = run(json.loads(checked_json(args.input, (RECONSTRUCTION_SHA256, PUBLIC_BUNDLE_SHA256))),
                           {c["caseId"]: c for c in map(json.loads, checked_json(args.cases, CASES_SHA256).splitlines())})
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    # Only the pinned public-fixture texts can reach this optional token-counter input.
    (args.output / "public-contexts.json").write_text(json.dumps(contexts, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({name: r["summary"] for name, r in result["variants"].items()}, ensure_ascii=False))


if __name__ == "__main__":
    main()
