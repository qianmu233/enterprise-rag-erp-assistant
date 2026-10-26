import importlib.util
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("experiments", Path(__file__).resolve().parents[1] / "rag_offline_experiments.py")
exp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exp)


class OfflineExperimentTest(unittest.TestCase):
    def metadata(self, **overrides):
        result = dict(ent_code="t", knowledge_base_id="k", document_id="d", document_version=1,
                      chunk_id="c", chunk_index=0, source="s.txt", embedding_model="m")
        result.update(overrides)
        return result

    def test_neighbor_recovers_subject_without_dropping_anchor(self):
        previous = dict(metadata=self.metadata(chunk_id="previous"), text="单文件上")
        anchor = dict(source="s.txt", chunkId="anchor", chunkIndex=1, cosine=.5, text="限为 500MB")
        chunks = {"previous": previous, "anchor": dict(metadata=self.metadata(chunk_id="anchor", chunk_index=1), text=anchor["text"])}
        result = exp.expand_previous([anchor], chunks, ("t", "k"), "m", {("t", "k", "d", 1)})
        self.assertEqual(["previous", "anchor"], [r["chunkId"] for r in result])
        self.assertIn("单文件上限为500mb", exp.normalize("".join(r["text"] for r in result)))

    def test_neighbor_rejects_wrong_scope_version_model_and_incomplete_identity(self):
        anchor = dict(source="s.txt", chunkId="anchor", chunkIndex=1, cosine=.5, text="anchor")
        for overrides in [dict(ent_code="other"), dict(knowledge_base_id="other"), dict(document_version=2),
                          dict(document_id="other"), dict(embedding_model="other"), dict(chunk_id=""), dict(source="other")]:
            with self.subTest(overrides=overrides):
                chunks = {"anchor": dict(metadata=self.metadata(chunk_id="anchor", chunk_index=1)),
                          "previous": dict(metadata=self.metadata(**overrides), text="sensitive")}
                self.assertEqual([anchor], exp.expand_previous([anchor], chunks, ("t", "k"), "m", {("t", "k", "d", 1)}))

    def test_ambiguous_previous_chunk_fails_closed(self):
        anchor = dict(source="s.txt", chunkId="anchor", chunkIndex=1, cosine=.5, text="anchor")
        chunks = {"anchor": dict(metadata=self.metadata(chunk_id="anchor", chunk_index=1)),
                  "a":dict(metadata=self.metadata(chunk_id="a"), text="a"),
                  "b":dict(metadata=self.metadata(chunk_id="b"), text="b")}
        self.assertEqual([anchor], exp.expand_previous([anchor], chunks, ("t", "k"), "m", {("t", "k", "d", 1)}))

    def test_high_scoring_cross_tenant_and_stale_version_cannot_enter(self):
        metadata = {"good": self.metadata(chunk_id="good"), "cross": self.metadata(chunk_id="cross", ent_code="other"),
                    "old": self.metadata(chunk_id="old", document_version=2)}
        rows = [dict(chunkId="cross", cosine=.99),dict(chunkId="old", cosine=.98),dict(chunkId="good", cosine=.3)]
        result = exp.select(rows, metadata, ("t", "k"), "m", {("t", "k", "d", 1)}, .16, 8, 24)
        self.assertEqual(["good"], [x["chunkId"] for x in result])

    def test_threshold_remains_strict_and_not_a_fallback(self):
        metadata = {"c":self.metadata()}
        self.assertEqual([],exp.select([dict(chunkId="c",cosine=.25)],metadata,("t","k"),"m",{("t","k","d",1)},.25,8,24))

    def test_source_diversity_then_score_sorting_matches_current_pipeline(self):
        metadata={"a":self.metadata(chunk_id="a"),"b":self.metadata(chunk_id="b"),"c":self.metadata(chunk_id="c",document_id="other")}
        rows=[dict(chunkId="a",cosine=.9),dict(chunkId="b",cosine=.8),dict(chunkId="c",cosine=.7)]
        result=exp.select(rows,metadata,("t","k"),"m",{("t","k","d",1),("t","k","other",1)},.25,2,24)
        self.assertEqual(["a","c"],[r["chunkId"] for r in result])

    def test_unknown_input_identity_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/"input.json";p.write_text("{}")
            with self.assertRaises(ValueError):exp.checked_json(p,"0"*64)

    def test_portable_bundle_reproduces_baseline_and_the_boundary_counterexample(self):
        root=Path(__file__).resolve().parents[2]
        bundle=json.loads(exp.checked_json(root/'src/test/resources/rag-eval/diagnostics/reconstruction-v1.json',exp.PUBLIC_BUNDLE_SHA256))
        cases={c['caseId']:c for c in map(json.loads,exp.checked_json(root/'src/test/resources/rag-eval/v1/cases.jsonl',exp.CASES_SHA256).splitlines())}
        result,_=exp.run(bundle,cases)
        self.assertAlmostEqual(0.9444444444444444,result['variants']['baseline']['summary']['documentRecallAt5'])
        self.assertAlmostEqual(0.8759259259259259,result['variants']['baseline']['summary']['documentMrrAt5'])
        selected={name:next(c for c in r['cases'] if c['caseId']=='paraphrase-001') for name,r in result['variants'].items()}
        self.assertFalse(selected['baseline']['singleFileSubject500MB'])
        self.assertTrue(selected['previous_neighbor_one']['singleFileSubject500MB'])

    def fixture_lf(self):
        root = Path(__file__).resolve().parents[2]
        return (root / 'src/test/resources/rag-eval/diagnostics/reconstruction-v1.json').read_bytes().replace(b'\r\n', b'\n')

    def checked_fixture_bytes(self, raw):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'fixture.json'
            path.write_bytes(raw)
            return exp.checked_json(path, exp.PUBLIC_BUNDLE_SHA256)

    def test_fixed_fixture_lf_is_accepted(self):
        lf = self.fixture_lf()
        self.assertNotIn(b'\r', lf)
        self.assertEqual(lf.decode('utf-8'), self.checked_fixture_bytes(lf))

    def test_fixed_fixture_crlf_is_accepted(self):
        lf = self.fixture_lf()
        self.assertEqual(lf.decode('utf-8'), self.checked_fixture_bytes(lf.replace(b'\n', b'\r\n')))

    def test_fixed_fixture_canonical_digests_are_equal_and_pinned(self):
        lf = self.fixture_lf()
        crlf = lf.replace(b'\n', b'\r\n')
        # Anchors are the independently verified original CRLF and corresponding LF identities.
        self.assertEqual('54d09f8e53a2221fbf47296b105b128a405bb737017680aae49f8d422a76fc2e', hashlib.sha256(crlf).hexdigest())
        self.assertEqual('dea815e16d67ae71b0d84d452c71c8d229db689113fb281e7bd6b3606e448c3e', exp.PUBLIC_BUNDLE_SHA256)
        for raw in (lf, crlf):
            self.assertEqual(exp.PUBLIC_BUNDLE_SHA256, hashlib.sha256(exp.canonical_lf_bytes(raw)).hexdigest())

    def test_fixed_fixture_body_edits_deletion_replacement_and_truncation_are_rejected(self):
        lf = self.fixture_lf()
        edited = json.loads(lf)
        del edited['chunks'][0]
        mutations = {
            'field': lf.replace(b'"schema"', b'"schemb"', 1),
            'space': lf.replace(b'  ', b'   ', 1),
            'data': lf.replace(b'public-fixture-reconstruction-v1', b'public-fixture-reconstruction-v2', 1),
            'deleted_chunk': json.dumps(edited, ensure_ascii=False).encode('utf-8'),
            'truncated': lf[:-16],
            'removed_final_newline': lf[:-1],
            'bom': b'\xef\xbb\xbf' + lf,
        }
        for kind, raw in mutations.items():
            with self.subTest(kind=kind):
                self.assertNotEqual(lf, raw)
                with self.assertRaisesRegex(ValueError, 'Input identity mismatch'):
                    self.checked_fixture_bytes(raw)

    def test_fixed_fixture_abnormal_line_endings_are_rejected(self):
        lf = self.fixture_lf()
        for raw in (lf.replace(b'\n', b'\r'), lf.replace(b'\n', b'\r\n', 1),
                    lf.replace(b'\n', b'\r\r\n'), lf.replace(b'\n', b'\n\n', 1)):
            with self.subTest(digest=hashlib.sha256(raw).hexdigest()):
                with self.assertRaises(ValueError):
                    self.checked_fixture_bytes(raw)

    def test_git_index_lf_fixture_passes_integrity_check(self):
        root = Path(__file__).resolve().parents[2]
        staged = subprocess.check_output(['git', 'show', ':src/test/resources/rag-eval/diagnostics/reconstruction-v1.json'], cwd=root)
        self.assertNotIn(b'\r', staged)
        self.assertEqual(self.fixture_lf(), staged)
        self.assertEqual(staged.decode('utf-8'), self.checked_fixture_bytes(staged))

    def test_fixed_cases_lf_and_crlf_are_accepted(self):
        root = Path(__file__).resolve().parents[2]
        lf = (root / 'src/test/resources/rag-eval/v1/cases.jsonl').read_bytes().replace(b'\r\n', b'\n')
        self.assertEqual('ef62c6f48d5f008bfbdc5821a2c52538eb8f673f6c8ebedb9b8e990e00b0c8ed', hashlib.sha256(lf.replace(b'\n', b'\r\n')).hexdigest())
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'cases.jsonl'
            for raw in (lf, lf.replace(b'\n', b'\r\n')):
                path.write_bytes(raw)
                self.assertEqual(lf.decode('utf-8'), exp.checked_json(path, exp.CASES_SHA256))

    def test_all_experiments_are_identical_for_lf_and_crlf(self):
        root = Path(__file__).resolve().parents[2]
        cases_text = exp.checked_json(root / 'src/test/resources/rag-eval/v1/cases.jsonl', exp.CASES_SHA256)
        cases = {c['caseId']: c for c in map(json.loads, cases_text.splitlines())}
        lf = self.fixture_lf()
        first = exp.run(json.loads(self.checked_fixture_bytes(lf)), cases)
        second = exp.run(json.loads(self.checked_fixture_bytes(lf.replace(b'\n', b'\r\n'))), cases)
        self.assertEqual(first, second)


if __name__ == "__main__":unittest.main()
