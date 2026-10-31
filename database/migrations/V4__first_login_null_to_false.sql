-- ============================================================
-- V4: Normalize users.first_login NULL -> FALSE
-- ============================================================
-- Problem
--   DataSeeder's Lombok @Builder ignored the field initializer
--   (`firstLogin = true`), so the 9 seeded accounts were stored with
--   first_login = NULL instead of an explicit boolean.
--
-- Why this correction is safe (behavior-identical)
--   Every reader of first_login uses null-safe/boolean-truthy logic:
--     * AuthService:106,190  -> Boolean.TRUE.equals(user.getFirstLogin())
--     * DeveloperService:562 -> Boolean.TRUE.equals(user.getFirstLogin())
--     * Frontend AuthContext/App -> `user.firstLogin || false`,
--       `user.firstLogin && ...`, `=== true`
--   In ALL of those paths NULL and FALSE evaluate identically, so
--   converting NULL -> FALSE cannot change any workflow
--   (no forced password change appears or disappears).
--   New accounts are unaffected: DeveloperService sets firstLogin
--   explicitly at creation.
--
-- Safety
--   * UPDATE touches ONLY rows where first_login IS NULL (9 rows).
--   * No rows are reset, dropped, truncated or deleted; no other column
--     and no other table is modified.
--
-- Verification (run after applying):
--   SELECT COUNT(*) FROM users WHERE first_login IS NULL;   -- expect 0
--   SELECT COUNT(*) FROM users;                             -- expect unchanged (22)
-- ============================================================

UPDATE users
SET first_login = FALSE
WHERE first_login IS NULL;
