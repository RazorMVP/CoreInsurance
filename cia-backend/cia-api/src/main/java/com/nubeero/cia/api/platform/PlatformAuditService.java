package com.nubeero.cia.api.platform;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PlatformAuditService {

    public record PlatformAuditEntry(UUID id, String action, String targetSchema,
                                     String actorUsername, String actorRealm, String detail,
                                     String sourceIp, Instant at) {}

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
            + " FROM public.platform_audit_log ORDER BY at DESC LIMIT ?",
            (rs, i) -> new PlatformAuditEntry(
                rs.getObject("id", UUID.class),
                rs.getString("action"),
                rs.getString("target_schema"),
                rs.getString("actor_username"),
                rs.getString("actor_realm"),
                rs.getString("detail"),
                rs.getString("source_ip"),
                rs.getTimestamp("at").toInstant()),
            limit);
    }
}
