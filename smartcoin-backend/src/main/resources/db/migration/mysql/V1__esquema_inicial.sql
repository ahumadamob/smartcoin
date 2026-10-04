-- Esquema inicial de Smartcoin. Ver docs/modelo-de-datos.md.
-- Una migración aplicada no se edita: cualquier cambio va en una migración nueva.

CREATE TABLE app_user (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    email                VARCHAR(254) NOT NULL,
    password_hash        VARCHAR(100) NOT NULL,
    must_change_password BOOLEAN      NOT NULL,
    credentials_version  INT          NOT NULL DEFAULT 0,
    start_period         CHAR(7)      NOT NULL,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uk_app_user_email UNIQUE (email)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;

CREATE TABLE account (
    id              BIGINT         NOT NULL AUTO_INCREMENT,
    user_id         BIGINT         NOT NULL,
    name            VARCHAR(100)   NOT NULL,
    type            VARCHAR(20)    NOT NULL,
    currency        CHAR(3)        NOT NULL,
    opening_date    DATE           NOT NULL,
    initial_balance DECIMAL(19, 2) NOT NULL,
    created_at      DATETIME(6)    NOT NULL,
    updated_at      DATETIME(6)    NOT NULL,
    CONSTRAINT pk_account PRIMARY KEY (id),
    CONSTRAINT fk_account_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT uk_account_user_name UNIQUE (user_id, name),
    CONSTRAINT ck_account_type CHECK (type IN ('BANK', 'DIGITAL_WALLET', 'CASH')),
    CONSTRAINT ck_account_currency CHECK (currency IN ('ARS', 'USD'))
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;

CREATE TABLE category (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    name       VARCHAR(60) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_category PRIMARY KEY (id),
    CONSTRAINT fk_category_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT uk_category_user_name UNIQUE (user_id, name)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;

CREATE TABLE budget_item (
    id                       BIGINT         NOT NULL AUTO_INCREMENT,
    user_id                  BIGINT         NOT NULL,
    name                     VARCHAR(100)   NOT NULL,
    kind                     VARCHAR(10)    NOT NULL,
    category_id              BIGINT         NULL,
    default_account_id       BIGINT         NOT NULL,
    periodicity              VARCHAR(12)    NOT NULL,
    due_day                  SMALLINT       NOT NULL,
    due_month_offset         SMALLINT       NOT NULL,
    start_period             CHAR(7)        NOT NULL,
    end_period               CHAR(7)        NULL,
    installments_total       SMALLINT       NULL,
    first_installment_number SMALLINT       NULL,
    estimation_rule          VARCHAR(20)    NOT NULL,
    current_amount           DECIMAL(19, 2) NOT NULL,
    generated_until          CHAR(7)        NULL,
    created_at               DATETIME(6)    NOT NULL,
    updated_at               DATETIME(6)    NOT NULL,
    CONSTRAINT pk_budget_item PRIMARY KEY (id),
    CONSTRAINT fk_budget_item_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_budget_item_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE RESTRICT,
    CONSTRAINT fk_budget_item_default_account FOREIGN KEY (default_account_id) REFERENCES account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_budget_item_kind CHECK (kind IN ('INCOME', 'EXPENSE')),
    CONSTRAINT ck_budget_item_periodicity CHECK (periodicity IN ('MONTHLY', 'BIMONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'ANNUAL')),
    CONSTRAINT ck_budget_item_estimation_rule CHECK (estimation_rule IN ('LAST_VALUE', 'AVERAGE_LAST_3')),
    CONSTRAINT ck_budget_item_due_day CHECK (due_day BETWEEN 1 AND 31),
    CONSTRAINT ck_budget_item_due_month_offset CHECK (due_month_offset IN (0, -1)),
    CONSTRAINT ck_budget_item_current_amount CHECK (current_amount >= 0),
    CONSTRAINT ck_budget_item_end_period CHECK (end_period IS NULL OR end_period >= start_period),
    CONSTRAINT ck_budget_item_installments_both CHECK ((installments_total IS NULL) = (first_installment_number IS NULL)),
    CONSTRAINT ck_budget_item_installments_range CHECK (installments_total IS NULL
        OR (installments_total >= 1 AND first_installment_number BETWEEN 1 AND installments_total))
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;

CREATE TABLE budget_period (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    period_month CHAR(7)     NOT NULL,
    status       VARCHAR(10) NOT NULL,
    closed_at    DATETIME(6) NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    CONSTRAINT pk_budget_period PRIMARY KEY (id),
    CONSTRAINT fk_budget_period_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT uk_budget_period_user_month UNIQUE (user_id, period_month),
    CONSTRAINT ck_budget_period_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_budget_period_closed_at CHECK ((status = 'OPEN') = (closed_at IS NULL))
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;

CREATE TABLE account_closing (
    id               BIGINT         NOT NULL AUTO_INCREMENT,
    user_id          BIGINT         NOT NULL,
    period_id        BIGINT         NOT NULL,
    account_id       BIGINT         NOT NULL,
    computed_balance DECIMAL(19, 2) NOT NULL,
    real_balance     DECIMAL(19, 2) NOT NULL,
    difference       DECIMAL(19, 2) NOT NULL,
    created_at       DATETIME(6)    NOT NULL,
    CONSTRAINT pk_account_closing PRIMARY KEY (id),
    CONSTRAINT fk_account_closing_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_account_closing_period FOREIGN KEY (period_id) REFERENCES budget_period (id) ON DELETE RESTRICT,
    CONSTRAINT fk_account_closing_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE RESTRICT,
    CONSTRAINT uk_account_closing_period_account UNIQUE (period_id, account_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;

CREATE TABLE budget_entry (
    id                  BIGINT         NOT NULL AUTO_INCREMENT,
    user_id             BIGINT         NOT NULL,
    period_id           BIGINT         NOT NULL,
    budget_item_id      BIGINT         NULL,
    origin              VARCHAR(20)    NOT NULL,
    name                VARCHAR(100)   NULL,
    kind                VARCHAR(10)    NOT NULL,
    category_id         BIGINT         NULL,
    account_id          BIGINT         NOT NULL,
    due_date            DATE           NOT NULL,
    budgeted_amount     DECIMAL(19, 2) NOT NULL,
    is_manual           BOOLEAN        NOT NULL DEFAULT FALSE,
    status              VARCHAR(15)    NOT NULL,
    consolidated_amount DECIMAL(19, 2) NULL,
    consolidated_at     DATETIME(6)    NULL,
    closing_resolution  VARCHAR(15)    NULL,
    installment_number  SMALLINT       NULL,
    source_entry_id     BIGINT         NULL,
    source_closing_id   BIGINT         NULL,
    created_at          DATETIME(6)    NOT NULL,
    updated_at          DATETIME(6)    NOT NULL,
    CONSTRAINT pk_budget_entry PRIMARY KEY (id),
    CONSTRAINT fk_budget_entry_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_budget_entry_period FOREIGN KEY (period_id) REFERENCES budget_period (id) ON DELETE RESTRICT,
    CONSTRAINT fk_budget_entry_budget_item FOREIGN KEY (budget_item_id) REFERENCES budget_item (id) ON DELETE RESTRICT,
    CONSTRAINT fk_budget_entry_category FOREIGN KEY (category_id) REFERENCES category (id) ON DELETE RESTRICT,
    CONSTRAINT fk_budget_entry_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_budget_entry_source_entry FOREIGN KEY (source_entry_id) REFERENCES budget_entry (id) ON DELETE RESTRICT,
    CONSTRAINT fk_budget_entry_source_closing FOREIGN KEY (source_closing_id) REFERENCES account_closing (id) ON DELETE RESTRICT,
    CONSTRAINT uk_budget_entry_item_period UNIQUE (budget_item_id, period_id),
    CONSTRAINT ck_budget_entry_origin CHECK (origin IN ('RECURRING', 'ONE_OFF', 'CARRIED_OVER', 'CLOSING_DIFFERENCE')),
    CONSTRAINT ck_budget_entry_kind CHECK (kind IN ('INCOME', 'EXPENSE')),
    CONSTRAINT ck_budget_entry_status CHECK (status IN ('PENDING', 'CONSOLIDATED')),
    CONSTRAINT ck_budget_entry_closing_resolution_values CHECK (closing_resolution IS NULL
        OR closing_resolution IN ('CARRY_OVER', 'CLOSE_AS_IS')),
    CONSTRAINT ck_budget_entry_recurring_item CHECK ((origin = 'RECURRING') = (budget_item_id IS NOT NULL)),
    CONSTRAINT ck_budget_entry_name CHECK (budget_item_id IS NOT NULL OR name IS NOT NULL),
    CONSTRAINT ck_budget_entry_budgeted_amount CHECK (budgeted_amount >= 0),
    CONSTRAINT ck_budget_entry_consolidated_amount CHECK ((status = 'CONSOLIDATED') = (consolidated_amount IS NOT NULL)),
    CONSTRAINT ck_budget_entry_consolidated_at CHECK ((status = 'CONSOLIDATED') = (consolidated_at IS NOT NULL)),
    CONSTRAINT ck_budget_entry_closing_resolution CHECK (closing_resolution IS NULL OR status = 'CONSOLIDATED')
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;

CREATE INDEX idx_budget_entry_user_period ON budget_entry (user_id, period_id);
CREATE INDEX idx_budget_entry_item_status_period ON budget_entry (budget_item_id, status, period_id);
CREATE INDEX idx_budget_entry_user_status_due ON budget_entry (user_id, status, due_date);

CREATE TABLE movement (
    id            BIGINT         NOT NULL AUTO_INCREMENT,
    user_id       BIGINT         NOT NULL,
    entry_id      BIGINT         NOT NULL,
    account_id    BIGINT         NOT NULL,
    movement_date DATE           NOT NULL,
    amount        DECIMAL(19, 2) NOT NULL,
    note          VARCHAR(200)   NULL,
    created_at    DATETIME(6)    NOT NULL,
    updated_at    DATETIME(6)    NOT NULL,
    CONSTRAINT pk_movement PRIMARY KEY (id),
    CONSTRAINT fk_movement_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_movement_entry FOREIGN KEY (entry_id) REFERENCES budget_entry (id) ON DELETE RESTRICT,
    CONSTRAINT fk_movement_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_movement_amount CHECK (amount > 0)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;

CREATE INDEX idx_movement_account_date ON movement (account_id, movement_date);
CREATE INDEX idx_movement_entry ON movement (entry_id);

CREATE TABLE transfer (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    user_id           BIGINT         NOT NULL,
    source_account_id BIGINT         NOT NULL,
    target_account_id BIGINT         NOT NULL,
    transfer_date     DATE           NOT NULL,
    source_amount     DECIMAL(19, 2) NOT NULL,
    target_amount     DECIMAL(19, 2) NOT NULL,
    note              VARCHAR(200)   NULL,
    created_at        DATETIME(6)    NOT NULL,
    updated_at        DATETIME(6)    NOT NULL,
    CONSTRAINT pk_transfer PRIMARY KEY (id),
    CONSTRAINT fk_transfer_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_source_account FOREIGN KEY (source_account_id) REFERENCES account (id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_target_account FOREIGN KEY (target_account_id) REFERENCES account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_transfer_accounts CHECK (source_account_id <> target_account_id),
    CONSTRAINT ck_transfer_amounts CHECK (source_amount > 0 AND target_amount > 0)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_ci;

CREATE INDEX idx_transfer_source_date ON transfer (source_account_id, transfer_date);
CREATE INDEX idx_transfer_target_date ON transfer (target_account_id, transfer_date);
