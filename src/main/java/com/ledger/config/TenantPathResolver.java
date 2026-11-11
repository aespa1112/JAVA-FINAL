package com.ledger.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Resolves tenant database paths from a trusted tenant identifier.
 * <p>
 * Path generation uses a canonical representation of tenantId to avoid
 * case/whitespace variants mapping to different files. The resulting path
 * is always under a fixed tenant root and is checked against path traversal.
 * <p>
 * Note: UUID name-based generation provides practical uniqueness for the
 * expected tenant-id space; it is not claimed to be collision-free in the
 * mathematical sense for arbitrary inputs.
 */
public final class TenantPathResolver {

    private static final Logger LOG = Logger.getLogger(TenantPathResolver.class.getName());

    /** Fixed root directory for all tenant databases. */
    public static final String TENANT_ROOT = "data/tenants";

    private TenantPathResolver() {
    }

    /**
     * Canonical form used for path derivation and authorization lookups.
     * - trim leading/trailing whitespace
     * - lowercase (Locale.ROOT)
     * - reject null / blank / illegal patterns
     */
    public static String normalizeTenantId(String tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        String t = tenantId.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        // Reject path-like or absolute forms early
        if (t.contains("..") || t.contains("/") || t.contains("\\")
                || t.contains(":") || t.startsWith(".") ) {
            throw new IllegalArgumentException("tenantId contains illegal path characters: " + tenantId);
        }
        // Keep a conservative character set for the logical id
        if (!t.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("tenantId has illegal format: " + tenantId);
        }
        return t.toLowerCase(Locale.ROOT);
    }

    /**
     * Derive a stable relative database path under TENANT_ROOT from a tenantId.
     * Caller must have already authorized the tenant.
     */
    public static String resolveDbPath(String tenantId) {
        String canonical = normalizeTenantId(tenantId);
        // UUID v3 (name-based, MD5) over a fixed namespace + canonical id.
        // Practical uniqueness for tenant identifiers; not advertised as absolute collision-free.
        UUID uuid = UUID.nameUUIDFromBytes(
                ("ledger-tenant:" + canonical).getBytes(StandardCharsets.UTF_8));
        String fileName = uuid.toString() + ".db";
        Path candidate = Paths.get(TENANT_ROOT, fileName).normalize();
        Path root = Paths.get(TENANT_ROOT).toAbsolutePath().normalize();
        Path abs = candidate.toAbsolutePath().normalize();
        if (!abs.startsWith(root)) {
            LOG.severe("Resolved path escapes tenant root: " + abs);
            throw new SecurityException("Resolved tenant path escapes tenant root");
        }
        // Return relative form used by the rest of the system
        return candidate.toString().replace('\\', '/');
    }

    /**
     * Validate that a caller-supplied path is exactly the path that would be
     * produced for the given tenant. Used to reject arbitrary external dbPath.
     */
    public static boolean isPathOwnedByTenant(String tenantId, String dbPath) {
        if (dbPath == null || dbPath.isBlank()) {
            return false;
        }
        try {
            String expected = resolveDbPath(tenantId);
            Path p1 = Paths.get(expected).normalize();
            Path p2 = Paths.get(dbPath).normalize();
            return p1.equals(p2);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reject absolute paths or paths that try to leave the tenant root.
     */
    public static void assertSafePath(String dbPath) {
        if (dbPath == null || dbPath.isBlank()) {
            throw new SecurityException("dbPath must not be blank");
        }
        if (":memory:".equalsIgnoreCase(dbPath.trim())) {
            return; // allow in-memory for tests
        }
        Path p = Paths.get(dbPath).normalize();
        if (p.isAbsolute()) {
            throw new SecurityException("Absolute database paths are not allowed: " + dbPath);
        }
        String s = p.toString().replace('\\', '/');
        if (s.contains("..") || s.startsWith("/") || s.startsWith("\\")) {
            throw new SecurityException("Path traversal rejected: " + dbPath);
        }
        // Tenant DBs must live under TENANT_ROOT (or legacy data/ for system/default)
        if (!s.startsWith(TENANT_ROOT + "/") && !s.startsWith("data/")) {
            throw new SecurityException("Database path outside allowed roots: " + dbPath);
        }
    }
}
