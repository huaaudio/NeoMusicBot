package io.github.huaaudio.neomusicbot.audio.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.github.huaaudio.neomusicbot.audio.media.YtDlpException.Kind;

/** Platform-specific proof that an account-cookie file is not shared. */
final class CookieFilePermissionPolicy
{
    private static final String WINDOWS_SYSTEM = "NT AUTHORITY\\SYSTEM";
    private static final String WINDOWS_ADMINISTRATORS = "BUILTIN\\ADMINISTRATORS";

    private CookieFilePermissionPolicy() {}

    static void validateOwnerOnly(Path file, String message) throws IOException
    {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null)
        {
            Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_EXECUTE))
                throw new YtDlpException(Kind.CONFIGURATION, message);
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(
                file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null || !isOwnerOnlyAcl(acl.getOwner(), acl.getAcl()))
            throw new YtDlpException(Kind.CONFIGURATION, message);
    }

    /**
     * Effective allow entries may name only the owner or Windows' two
     * privileged maintenance identities. Deny and inherit-only entries do not
     * grant access to this file. Unknown ACL semantics therefore fail closed.
     */
    static boolean isOwnerOnlyAcl(UserPrincipal owner, List<AclEntry> entries)
    {
        if (owner == null || entries == null)
            return false;
        for (AclEntry entry : entries)
        {
            if (entry == null || entry.principal() == null)
                return false;
            if (entry.type() == AclEntryType.ALLOW
                    && !entry.flags().contains(AclEntryFlag.INHERIT_ONLY)
                    && !isTrustedPrincipal(owner, entry.principal()))
                return false;
        }
        return true;
    }

    private static boolean isTrustedPrincipal(UserPrincipal owner, UserPrincipal principal)
    {
        if (owner.equals(principal))
            return true;
        String name = principal.getName();
        if (name == null)
            return false;
        String normalized = name.replace('/', '\\').toUpperCase(Locale.ROOT);
        return WINDOWS_SYSTEM.equals(normalized) || WINDOWS_ADMINISTRATORS.equals(normalized);
    }
}
