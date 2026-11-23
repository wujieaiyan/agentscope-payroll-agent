-- 强制首改密：新建/重置密码置 TRUE，改密成功后清除；存量用户迁移时保持 FALSE（D8）。
ALTER TABLE app_users
    ADD COLUMN must_change_password TINYINT(1) NOT NULL DEFAULT 0;
