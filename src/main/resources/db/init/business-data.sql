-- PUBLIC SYNTHETIC FIXTURE, generated 2026-10-26. Not real enterprise data.
-- Contacts are non-person identifiers; phone values are non-dialable labels.
CREATE TABLE IF NOT EXISTS b_sales_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32)    NOT NULL COMMENT '销售订单号',
    order_date    DATE           NOT NULL COMMENT '下单日期',
    customer_name VARCHAR(100)   NOT NULL COMMENT '客户名称',
    product_name  VARCHAR(100)            COMMENT '产品名称',
    qty           DECIMAL(12,2)           COMMENT '数量',
    total_amount  DECIMAL(14,2)           COMMENT '订单总金额',
    status        VARCHAR(20)             COMMENT '订单状态',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_customer_name (customer_name),
    INDEX idx_order_date (order_date),
    INDEX idx_ent_code (ent_code)
) COMMENT '销售订单主表';

CREATE TABLE IF NOT EXISTS b_sales_order_detail (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32)    NOT NULL COMMENT '销售订单号',
    product_code  VARCHAR(32)             COMMENT '产品编码',
    product_name  VARCHAR(100)            COMMENT '产品名称',
    qty           DECIMAL(12,2)           COMMENT '数量',
    unit_price    DECIMAL(12,2)           COMMENT '单价',
    amount        DECIMAL(14,2)           COMMENT '金额',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_order_no (order_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '销售订单明细表';

CREATE TABLE IF NOT EXISTS b_shipment (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32)    NOT NULL COMMENT '销售订单号',
    shipment_no   VARCHAR(32)    NOT NULL COMMENT '发货单号',
    ship_date     DATE                    COMMENT '发货日期',
    carrier       VARCHAR(50)             COMMENT '承运商',
    tracking_no   VARCHAR(64)             COMMENT '物流单号',
    shipped_qty   DECIMAL(12,2)           COMMENT '已发数量',
    status        VARCHAR(20)             COMMENT '发货状态',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_order_no (order_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '发货/物流表';

CREATE TABLE IF NOT EXISTS b_accounts_receivable (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_name     VARCHAR(100)  NOT NULL COMMENT '客户名称',
    invoice_no        VARCHAR(32)            COMMENT '发票号',
    invoice_date      DATE                   COMMENT '发票日期',
    receivable_amount DECIMAL(14,2)          COMMENT '应收金额',
    received_amount   DECIMAL(14,2)          COMMENT '已收金额',
    ent_code          VARCHAR(32)   NOT NULL COMMENT '租户编码',
    INDEX idx_customer_name (customer_name),
    INDEX idx_ent_code (ent_code)
) COMMENT '应收账款表';

CREATE TABLE IF NOT EXISTS b_purchase_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    po_no         VARCHAR(32)    NOT NULL COMMENT '采购订单号',
    po_date       DATE           NOT NULL COMMENT '采购日期',
    supplier_name VARCHAR(100)   NOT NULL COMMENT '供应商名称',
    total_amount  DECIMAL(14,2)           COMMENT '总金额',
    status        VARCHAR(20)             COMMENT '订单状态',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_supplier_name (supplier_name),
    INDEX idx_po_date (po_date),
    INDEX idx_ent_code (ent_code)
) COMMENT '采购订单主表';

CREATE TABLE IF NOT EXISTS b_purchase_order_detail (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    po_no         VARCHAR(32)    NOT NULL COMMENT '采购订单号',
    product_code  VARCHAR(32)             COMMENT '产品编码',
    product_name  VARCHAR(100)            COMMENT '产品名称',
    qty           DECIMAL(12,2)           COMMENT '数量',
    unit_price    DECIMAL(12,2)           COMMENT '单价',
    amount        DECIMAL(14,2)           COMMENT '金额',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_po_no (po_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '采购订单明细表';

CREATE TABLE IF NOT EXISTS b_purchase_receive (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    po_no         VARCHAR(32)    NOT NULL COMMENT '采购订单号',
    receive_no    VARCHAR(32)    NOT NULL COMMENT '收货单号',
    receive_date  DATE                    COMMENT '收货日期',
    product_name  VARCHAR(100)            COMMENT '产品名称',
    ordered_qty   DECIMAL(12,2)           COMMENT '订购数量',
    received_qty  DECIMAL(12,2)           COMMENT '已收数量',
    status        VARCHAR(20)             COMMENT '收货状态',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_po_no (po_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '采购收货表';

CREATE TABLE IF NOT EXISTS b_accounts_payable (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    supplier_name   VARCHAR(100)  NOT NULL COMMENT '供应商名称',
    invoice_no      VARCHAR(32)            COMMENT '发票号',
    invoice_date    DATE                   COMMENT '发票日期',
    payable_amount  DECIMAL(14,2)          COMMENT '应付金额',
    paid_amount     DECIMAL(14,2)          COMMENT '已付金额',
    ent_code        VARCHAR(32)   NOT NULL COMMENT '租户编码',
    INDEX idx_supplier_name (supplier_name),
    INDEX idx_ent_code (ent_code)
) COMMENT '应付账款表';

CREATE TABLE IF NOT EXISTS b_work_order (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    wo_no              VARCHAR(32)    NOT NULL COMMENT '生产工单号',
    product_code       VARCHAR(32)             COMMENT '产品编码',
    product_name       VARCHAR(100)            COMMENT '产品名称',
    planned_qty        DECIMAL(12,2)           COMMENT '计划数量',
    completed_qty      DECIMAL(12,2)           COMMENT '完成数量',
    scrap_qty          DECIMAL(12,2)           COMMENT '报废数量',
    status             VARCHAR(20)             COMMENT '工单状态',
    planned_start_date DATE                    COMMENT '计划开始日期',
    planned_end_date   DATE                    COMMENT '计划结束日期',
    actual_start_date  DATE                    COMMENT '实际开始日期',
    actual_end_date    DATE                    COMMENT '实际结束日期',
    ent_code           VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_wo_no (wo_no),
    INDEX idx_product_code (product_code),
    INDEX idx_ent_code (ent_code)
) COMMENT '生产工单表';

CREATE TABLE IF NOT EXISTS b_work_order_material (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    wo_no           VARCHAR(32)    NOT NULL COMMENT '生产工单号',
    material_code   VARCHAR(32)             COMMENT '物料编码',
    material_name   VARCHAR(100)            COMMENT '物料名称',
    required_qty    DECIMAL(12,2)           COMMENT '需求数量',
    issued_qty      DECIMAL(12,2)           COMMENT '已领数量',
    returned_qty    DECIMAL(12,2)           COMMENT '退料数量',
    unit            VARCHAR(10)             COMMENT '单位',
    ent_code        VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_wo_no (wo_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '工单用料表';

CREATE TABLE IF NOT EXISTS b_work_order_routing (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    wo_no           VARCHAR(32)    NOT NULL COMMENT '生产工单号',
    operation_seq   INT                     COMMENT '工序序号',
    operation_name  VARCHAR(50)             COMMENT '工序名称',
    work_center     VARCHAR(50)             COMMENT '工作中心',
    planned_qty     DECIMAL(12,2)           COMMENT '计划数量',
    completed_qty   DECIMAL(12,2)           COMMENT '完成数量',
    scrap_qty       DECIMAL(12,2)           COMMENT '报废数量',
    status          VARCHAR(20)             COMMENT '工序状态',
    operator        VARCHAR(50)             COMMENT '操作员',
    report_date     DATE                    COMMENT '报工日期',
    ent_code        VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_wo_no (wo_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '工单工序报工表';

CREATE TABLE IF NOT EXISTS b_inventory (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_code    VARCHAR(32)    NOT NULL COMMENT '产品编码',
    product_name    VARCHAR(100)            COMMENT '产品名称',
    warehouse       VARCHAR(50)             COMMENT '仓库名称/编码',
    lot_no          VARCHAR(32)             COMMENT '批次号',
    available_qty   DECIMAL(12,2)           COMMENT '可用库存',
    reserved_qty    DECIMAL(12,2)           COMMENT '预留库存',
    in_transit_qty  DECIMAL(12,2)           COMMENT '在途库存',
    safety_stock    DECIMAL(12,2)           COMMENT '安全库存',
    unit            VARCHAR(10)             COMMENT '单位',
    location        VARCHAR(32)             COMMENT '库位',
    ent_code        VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_product_code (product_code),
    INDEX idx_warehouse (warehouse),
    INDEX idx_ent_code (ent_code)
) COMMENT '库存表';

CREATE TABLE IF NOT EXISTS b_stock_movement (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    move_no         VARCHAR(32)    NOT NULL COMMENT '流水单号',
    move_type       VARCHAR(20)             COMMENT '类型（入库/出库/调拨）',
    product_code    VARCHAR(32)             COMMENT '产品编码',
    product_name    VARCHAR(100)            COMMENT '产品名称',
    qty             DECIMAL(12,2)           COMMENT '数量',
    from_warehouse  VARCHAR(50)             COMMENT '源仓库',
    to_warehouse    VARCHAR(50)             COMMENT '目标仓库',
    move_date       DATE                    COMMENT '流转日期',
    reference_no    VARCHAR(32)             COMMENT '关联单号',
    remark          VARCHAR(200)            COMMENT '备注',
    ent_code        VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_product_code (product_code),
    INDEX idx_move_date (move_date),
    INDEX idx_ent_code (ent_code)
) COMMENT '出入库流水表';

CREATE TABLE IF NOT EXISTS b_quality_inspection (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    lot_no           VARCHAR(32)    NOT NULL COMMENT '批次号',
    product_code     VARCHAR(32)             COMMENT '产品编码',
    product_name     VARCHAR(100)            COMMENT '产品名称',
    inspection_type  VARCHAR(30)             COMMENT '检验类型',
    inspection_date  DATE                    COMMENT '检验日期',
    inspector        VARCHAR(50)             COMMENT '检验员',
    sample_qty       DECIMAL(12,2)           COMMENT '抽样数量',
    pass_qty         DECIMAL(12,2)           COMMENT '合格数量',
    defect_qty       DECIMAL(12,2)           COMMENT '不良数量',
    result           VARCHAR(20)             COMMENT '检验结果（合格/不合格）',
    remark           VARCHAR(200)            COMMENT '备注',
    ent_code         VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_lot_no (lot_no),
    INDEX idx_product_code (product_code),
    INDEX idx_inspection_date (inspection_date),
    INDEX idx_ent_code (ent_code)
) COMMENT '质检记录表';

CREATE TABLE IF NOT EXISTS b_quality_defect_detail (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    lot_no           VARCHAR(32)    NOT NULL COMMENT '批次号',
    defect_code      VARCHAR(32)             COMMENT '不良代码',
    defect_name      VARCHAR(50)             COMMENT '不良名称',
    defect_qty       DECIMAL(12,2)           COMMENT '不良数量',
    defect_level     VARCHAR(20)             COMMENT '不良等级',
    handling_method  VARCHAR(50)             COMMENT '处理方式',
    remark           VARCHAR(200)            COMMENT '备注',
    ent_code         VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_lot_no (lot_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '质检不良明细表';

CREATE TABLE IF NOT EXISTS b_after_sales_ticket (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_no         VARCHAR(32)    NOT NULL COMMENT '工单号',
    customer_name     VARCHAR(100)   NOT NULL COMMENT '客户名称',
    contact_person    VARCHAR(50)             COMMENT '联系人',
    contact_phone     VARCHAR(20)             COMMENT '联系电话',
    product_name      VARCHAR(100)            COMMENT '产品名称',
    serial_no         VARCHAR(64)             COMMENT '产品序列号',
    issue_type        VARCHAR(30)             COMMENT '问题类型',
    issue_description VARCHAR(500)            COMMENT '问题描述',
    root_cause        VARCHAR(500)            COMMENT '根本原因',
    solution          VARCHAR(500)            COMMENT '解决方案',
    status            VARCHAR(20)             COMMENT '工单状态',
    priority          VARCHAR(10)             COMMENT '优先级',
    created_date      DATE                    COMMENT '创建日期',
    resolved_date     DATE                    COMMENT '解决日期',
    handler           VARCHAR(50)             COMMENT '处理人',
    ent_code          VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_ticket_no (ticket_no),
    INDEX idx_customer_name (customer_name),
    INDEX idx_ent_code (ent_code)
) COMMENT '售后工单表';

CREATE TABLE IF NOT EXISTS b_return_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    return_no       VARCHAR(32)    NOT NULL COMMENT '退货单号',
    customer_name   VARCHAR(100)            COMMENT '客户名称',
    product_code    VARCHAR(32)             COMMENT '产品编码',
    product_name    VARCHAR(100)            COMMENT '产品名称',
    return_type     VARCHAR(20)             COMMENT '退换类型（退货/换货）',
    qty             DECIMAL(12,2)           COMMENT '数量',
    reason          VARCHAR(200)            COMMENT '退换原因',
    status          VARCHAR(20)             COMMENT '状态',
    created_date    DATE                    COMMENT '创建日期',
    ent_code        VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_product_code (product_code),
    INDEX idx_customer_name (customer_name),
    INDEX idx_ent_code (ent_code)
) COMMENT '退换货表';

CREATE TABLE IF NOT EXISTS b_finance_ledger (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    type          VARCHAR(10)             COMMENT '类型（收入/支出）',
    amount        DECIMAL(14,2)           COMMENT '金额',
    ledger_date   DATE                    COMMENT '记账日期',
    remark        VARCHAR(200)            COMMENT '备注',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_ledger_date (ledger_date),
    INDEX idx_type (type),
    INDEX idx_ent_code (ent_code)
) COMMENT '财务总账表';

CREATE TABLE IF NOT EXISTS b_payment_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_no      VARCHAR(32)    NOT NULL COMMENT '收款单号',
    customer_name   VARCHAR(100)            COMMENT '客户名称',
    payment_date    DATE                    COMMENT '收款日期',
    amount          DECIMAL(14,2)           COMMENT '收款金额',
    payment_method  VARCHAR(30)             COMMENT '收款方式',
    reference_no    VARCHAR(32)             COMMENT '关联单号',
    remark          VARCHAR(200)            COMMENT '备注',
    ent_code        VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_payment_date (payment_date),
    INDEX idx_customer_name (customer_name),
    INDEX idx_ent_code (ent_code)
) COMMENT '收款记录表';

CREATE TABLE IF NOT EXISTS b_outsourcing_order (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    oo_no              VARCHAR(32)    NOT NULL COMMENT '委外订单号',
    supplier_name      VARCHAR(100)   NOT NULL COMMENT '供应商名称',
    product_name       VARCHAR(100)            COMMENT '产品名称',
    process_name       VARCHAR(50)             COMMENT '加工工序名称',
    qty                DECIMAL(12,2)           COMMENT '数量',
    unit_price         DECIMAL(12,2)           COMMENT '单价',
    total_amount       DECIMAL(14,2)           COMMENT '总金额',
    completed_qty      DECIMAL(12,2)           COMMENT '已完成数量',
    status             VARCHAR(20)             COMMENT '订单状态',
    delivery_date      DATE                    COMMENT '交货日期',
    actual_return_date DATE                    COMMENT '实际回货日期',
    ent_code           VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_oo_no (oo_no),
    INDEX idx_supplier_name (supplier_name),
    INDEX idx_ent_code (ent_code)
) COMMENT '委外加工订单表';

CREATE TABLE IF NOT EXISTS b_outsourcing_material_flow (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    oo_no         VARCHAR(32)    NOT NULL COMMENT '委外订单号',
    flow_type     VARCHAR(20)             COMMENT '流转类型（发料/退料/回货）',
    product_name  VARCHAR(100)            COMMENT '产品名称',
    qty           DECIMAL(12,2)           COMMENT '数量',
    flow_date     DATE                    COMMENT '流转日期',
    remark        VARCHAR(200)            COMMENT '备注',
    ent_code      VARCHAR(32)    NOT NULL COMMENT '租户编码',
    INDEX idx_oo_no (oo_no),
    INDEX idx_ent_code (ent_code)
) COMMENT '委外来料退料流水表';

INSERT INTO b_sales_order (order_no, order_date, customer_name, product_name, qty, total_amount, status, ent_code) VALUES
('SO-DEMO-001', '2026-01-01', 'SYNTH-CUSTOMER-001', 'SYNTH-PRODUCT-001', 10, 100, '待处理', 'ENT001');

INSERT INTO b_sales_order_detail (order_no, product_code, product_name, qty, unit_price, amount, ent_code) VALUES
('SO-DEMO-001', 'DEMO-P001', 'SYNTH-PRODUCT-001', 10, 10, 100, 'ENT001');

INSERT INTO b_shipment (order_no, shipment_no, ship_date, carrier, tracking_no, shipped_qty, status, ent_code) VALUES
('SO-DEMO-001', 'SHIP-DEMO-001', '2026-01-01', 'SYNTH-CARRIER-001', 'TRACK-DEMO-001', 8, '待处理', 'ENT001');

INSERT INTO b_accounts_receivable (customer_name, invoice_no, invoice_date, receivable_amount, received_amount, ent_code) VALUES
('SYNTH-CUSTOMER-001', 'INV-DEMO-001', '2026-01-01', 100, 20, 'ENT001');

INSERT INTO b_purchase_order (po_no, po_date, supplier_name, total_amount, status, ent_code) VALUES
('PO-DEMO-001', '2026-01-01', 'SYNTH-SUPPLIER-001', 100, '待处理', 'ENT001');

INSERT INTO b_purchase_order_detail (po_no, product_code, product_name, qty, unit_price, amount, ent_code) VALUES
('PO-DEMO-001', 'DEMO-P001', 'SYNTH-PRODUCT-001', 10, 10, 100, 'ENT001');

INSERT INTO b_purchase_receive (po_no, receive_no, receive_date, product_name, ordered_qty, received_qty, status, ent_code) VALUES
('PO-DEMO-001', 'RECV-DEMO-001', '2026-01-01', 'SYNTH-PRODUCT-001', 10, 8, '待处理', 'ENT001');

INSERT INTO b_accounts_payable (supplier_name, invoice_no, invoice_date, payable_amount, paid_amount, ent_code) VALUES
('SYNTH-SUPPLIER-001', 'INV-DEMO-001', '2026-01-01', 100, 20, 'ENT001');

INSERT INTO b_work_order (wo_no, product_code, product_name, planned_qty, completed_qty, scrap_qty, status, planned_start_date, planned_end_date, actual_start_date, actual_end_date, ent_code) VALUES
('WO-DEMO-001', 'DEMO-P001', 'SYNTH-PRODUCT-001', 10, 8, 0, '待处理', '2026-01-01', '2026-01-01', '2026-01-01', '2026-01-01', 'ENT001');

INSERT INTO b_work_order_material (wo_no, material_code, material_name, required_qty, issued_qty, returned_qty, unit, ent_code) VALUES
('WO-DEMO-001', 'DEMO-M001', 'SYNTH-MATERIAL-001', 10, 8, 0, '件', 'ENT001');

INSERT INTO b_work_order_routing (wo_no, operation_seq, operation_name, work_center, planned_qty, completed_qty, scrap_qty, status, operator, report_date, ent_code) VALUES
('WO-DEMO-001', 1, 'SYNTHETIC', 'SYNTHETIC', 10, 8, 0, '待处理', 'SYNTH-OPERATOR-001', '2026-01-01', 'ENT001');

INSERT INTO b_inventory (product_code, product_name, warehouse, lot_no, available_qty, reserved_qty, in_transit_qty, safety_stock, unit, location, ent_code) VALUES
('DEMO-P001', 'SYNTH-PRODUCT-001', 'SYNTH-WAREHOUSE-001', 'LOT-DEMO-001', 120, 10, 0, 20, '件', 'SYNTH-LOC-001', 'ENT001');

INSERT INTO b_stock_movement (move_no, move_type, product_code, product_name, qty, from_warehouse, to_warehouse, move_date, reference_no, remark, ent_code) VALUES
('MOVE-DEMO-001', '入库', 'DEMO-P001', 'SYNTH-PRODUCT-001', 10, 'SYNTH-WAREHOUSE-001', 'SYNTH-WAREHOUSE-002', '2026-01-01', 'REF-DEMO-001', 'SYNTHETIC', 'ENT001');

INSERT INTO b_quality_inspection (lot_no, product_code, product_name, inspection_type, inspection_date, inspector, sample_qty, pass_qty, defect_qty, result, remark, ent_code) VALUES
('LOT-DEMO-001', 'DEMO-P001', 'SYNTH-PRODUCT-001', '入库检验', '2026-01-01', 'SYNTH-INSPECTOR-001', 10, 9, 1, '合格', 'SYNTHETIC', 'ENT001');

INSERT INTO b_quality_defect_detail (lot_no, defect_code, defect_name, defect_qty, defect_level, handling_method, remark, ent_code) VALUES
('LOT-DEMO-001', 'DEFECT-DEMO-001', 'SYNTHETIC', 1, '轻微', 'SYNTHETIC', 'SYNTHETIC', 'ENT001');

INSERT INTO b_after_sales_ticket (ticket_no, customer_name, contact_person, contact_phone, product_name, serial_no, issue_type, issue_description, root_cause, solution, status, priority, created_date, resolved_date, handler, ent_code) VALUES
('TICKET-DEMO-001', 'SYNTH-CUSTOMER-001', 'SYNTH-CONTACT-001', 'DEMO-PHONE-001', 'SYNTH-PRODUCT-001', 'SERIAL-DEMO-001', 'SYNTHETIC', 'SYNTHETIC', 'SYNTHETIC', 'SYNTHETIC', '待处理', '普通', '2026-01-01', '2026-01-01', 'SYNTH-HANDLER-001', 'ENT001');

INSERT INTO b_return_order (return_no, customer_name, product_code, product_name, return_type, qty, reason, status, created_date, ent_code) VALUES
('RETURN-DEMO-001', 'SYNTH-CUSTOMER-001', 'DEMO-P001', 'SYNTH-PRODUCT-001', '退货', 10, 'SYNTHETIC', '待处理', '2026-01-01', 'ENT001');

INSERT INTO b_finance_ledger (type, amount, ledger_date, remark, ent_code) VALUES
('收入', 100, '2026-01-01', 'SYNTHETIC', 'ENT001');

INSERT INTO b_payment_record (payment_no, customer_name, payment_date, amount, payment_method, reference_no, remark, ent_code) VALUES
('PAY-DEMO-001', 'SYNTH-CUSTOMER-001', '2026-01-01', 100, 'SYNTHETIC', 'REF-DEMO-001', 'SYNTHETIC', 'ENT001');

INSERT INTO b_outsourcing_order (oo_no, supplier_name, product_name, process_name, qty, unit_price, total_amount, completed_qty, status, delivery_date, actual_return_date, ent_code) VALUES
('OO-DEMO-001', 'SYNTH-SUPPLIER-001', 'SYNTH-PRODUCT-001', 'SYNTHETIC', 10, 10, 100, 8, '待处理', '2026-01-01', '2026-01-01', 'ENT001');

INSERT INTO b_outsourcing_material_flow (oo_no, flow_type, product_name, qty, flow_date, remark, ent_code) VALUES
('OO-DEMO-001', '发料', 'SYNTH-PRODUCT-001', 10, '2026-01-01', 'SYNTHETIC', 'ENT001');
