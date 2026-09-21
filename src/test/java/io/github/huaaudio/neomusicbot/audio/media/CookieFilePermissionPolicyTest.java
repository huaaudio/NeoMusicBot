/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package io.github.huaaudio.neomusicbot.audio.media;

import org.junit.jupiter.api.Test;

import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CookieFilePermissionPolicyTest
{
    private static final UserPrincipal OWNER = () -> "runner";
    private static final UserPrincipal SYSTEM = () -> "NT AUTHORITY\\SYSTEM";
    private static final UserPrincipal ADMINISTRATORS = () -> "BUILTIN\\Administrators";
    private static final UserPrincipal EVERYONE = () -> "Everyone";

    @Test
    public void acceptsOnlyOwnerAndPrivilegedWindowsMaintenancePrincipals()
    {
        assertTrue(CookieFilePermissionPolicy.isOwnerOnlyAcl(OWNER, List.of(
                allow(OWNER), allow(SYSTEM), allow(ADMINISTRATORS),
                entry(AclEntryType.DENY, EVERYONE),
                inheritedOnlyAllow(EVERYONE))));
    }

    @Test
    public void rejectsEffectiveReadAllowForAnyUntrustedPrincipal()
    {
        assertFalse(CookieFilePermissionPolicy.isOwnerOnlyAcl(OWNER,
                List.of(allow(OWNER), allow(EVERYONE))));
        assertFalse(CookieFilePermissionPolicy.isOwnerOnlyAcl(null, List.of()));
        assertFalse(CookieFilePermissionPolicy.isOwnerOnlyAcl(OWNER, null));
    }

    private static AclEntry allow(UserPrincipal principal)
    {
        return entry(AclEntryType.ALLOW, principal);
    }

    private static AclEntry inheritedOnlyAllow(UserPrincipal principal)
    {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(principal)
                .setPermissions(AclEntryPermission.READ_DATA)
                .setFlags(AclEntryFlag.INHERIT_ONLY)
                .build();
    }

    private static AclEntry entry(AclEntryType type, UserPrincipal principal)
    {
        return AclEntry.newBuilder()
                .setType(type)
                .setPrincipal(principal)
                .setPermissions(AclEntryPermission.READ_DATA)
                .build();
    }
}
