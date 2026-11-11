/**
 * The administrator: a normal account whose email is listed in {@code app.admin.emails}
 * (ADMIN_EMAILS). It sees counts only - how many users, how many are active - and never another
 * user's email, name, phone or money.
 * <ul>
 * <li>{@link com.spendanalyzer.admin.AdminAccess}: who is an administrator.</li>
 * <li>{@link com.spendanalyzer.admin.AdminStatsService}: the counts, computed by the database.</li>
 * <li>{@link com.spendanalyzer.admin.AdminController}: GET /api/admin/stats (404 for everybody else).</li>
 * </ul>
 */
package com.spendanalyzer.admin;
