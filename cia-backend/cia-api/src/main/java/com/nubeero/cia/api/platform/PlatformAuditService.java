package com.nubeero.cia.api.platform;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PlatformAuditService {

    public record PlatformAuditEntry(UUID id, String action, String targetSchema,
                                     String actorUsername, String actorRealm, String detail,
                                     String sourceIp, Instant at) {}

    /** Shared row-mapper for every read path — keeps the column→field mapping in one place. */
    private static final RowMapper<PlatformAuditEntry> ROW_MAPPER = (rs, i) -> new PlatformAuditEntry(
            rs.getObject("id", UUID.class),
            rs.getString("action"),
            rs.getString("target_schema"),
            rs.getString("actor_username"),
            rs.getString("actor_realm"),
            rs.getString("detail"),
            rs.getString("source_ip"),
            rs.getTimestamp("at").toInstant());

    private final JdbcTemplate jdbc;

    public PlatformAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Dual audit: structured log + schema-qualified insert into public.platform_audit_log. */
    public void record(String action, String targetSchema, String actor, String actorRealm,
                       String detailJson, String sourceIp) {
        log.info("platform-audit action={} target={} actor={} realm={} ip={}",
                action, targetSchema, actor, actorRealm, sourceIp);
        jdbc.update("INSERT INTO public.platform_audit_log"
            + " (action,target_schema,actor_username,actor_realm,detail,source_ip)"
            + " VALUES (?,?,?,?,?::jsonb,?)",
            action, targetSchema, actor, actorRealm, detailJson, sourceIp);
    }

    public List<PlatformAuditEntry> recent(int limit) {
        return jdbc.query("SELECT id,action,target_schema,actor_username,actor_realm,detail,source_ip,at"
            + " FROM public.platform_audit_log ORDER BY at DESC, id DESC LIMIT ?",
            ROW_MAPPER, limit);
    }

    /** Newest-first audit rows for a single target schema (backs the consolidated tenant detail). */
    public List<PlatformAuditEntry> recentForSchema(String targetSchema, int limit) {
        return jdbc.query("SELECT id,action,target_schema,actor_username,actor_realm,detail,source_ip,at"
            + " FROM public.platform_audit_log WHERE target_schema = ? ORDER BY at DESC, id DESC LIMIT ?",
            ROW_MAPPER, targetSchema, limit);
    }

    /**
     * Paged audit trail, newest first. When {@code targetSchema} is non-null, filters to that
     * tenant; when null, returns all actions (including user-targeted super-admin rows whose
     * {@code target_schema} is NULL).
     */
    public List<PlatformAuditEntry> recent(int page, int size, String targetSchema) {
        int offset = Math.max(0, page) * size;
        if (targetSchema == null) {
            return jdbc.query("SELECT id,action,target_schema,actor_username,actor_realm,detail,source_ip,at"
                + " FROM public.platform_audit_log ORDER BY at DESC, id DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, size, offset);
        }
        return jdbc.query("SELECT id,action,target_schema,actor_username,actor_realm,detail,source_ip,at"
            + " FROM public.platform_audit_log WHERE target_schema = ? ORDER BY at DESC, id DESC LIMIT ? OFFSET ?",
            ROW_MAPPER, targetSchema, size, offset);
    }

    /** Total audit-row count, optionally filtered to one target schema (for pagination total). */
    public long count(String targetSchema) {
        Long n = (targetSchema == null)
            ? jdbc.queryForObject("SELECT COUNT(*) FROM public.platform_audit_log", Long.class)
            : jdbc.queryForObject("SELECT COUNT(*) FROM public.platform_audit_log WHERE target_schema = ?",
                Long.class, targetSchema);
        return n == null ? 0L : n;
    }
}
