/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package com.jagrosh.jmusicbot.audio.media;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class YtDlpCookieFileSecurityTest
{
    @Test
    public void acceptsBoundedOwnerOnlyNetscapeFileAndRejectsUnsafeInputs() throws Exception
    {
        Path directory = Files.createTempDirectory("yt-dlp-cookie");
        Path valid = directory.resolve("cookies.txt");
        Files.writeString(valid, "# Netscape HTTP Cookie File\n", StandardCharsets.UTF_8);
        makeOwnerOnlyWhenSupported(valid);

        YtDlpMediaResolver.validateCookieFile(valid, MediaSource.YOUTUBE);

        assertGenericFailure(directory, directory);

        Path malformed = directory.resolve("malformed.txt");
        Files.writeString(malformed, "SESSDATA=must-not-appear-in-error\n", StandardCharsets.UTF_8);
        makeOwnerOnlyWhenSupported(malformed);
        assertGenericFailure(malformed, malformed);

        Path oversized = directory.resolve("oversized.txt");
        Files.write(oversized, new byte[4 * 1024 * 1024 + 1]);
        makeOwnerOnlyWhenSupported(oversized);
        assertGenericFailure(oversized, oversized);
    }

    @Test
    public void rejectsGroupOrOtherPermissionsOnPosix() throws Exception
    {
        Path file = Files.createTempFile("yt-dlp-cookie-permissions", ".txt");
        Files.writeString(file, "# Netscape HTTP Cookie File\n", StandardCharsets.UTF_8);
        PosixFileAttributeView posix =
                Files.getFileAttributeView(file, PosixFileAttributeView.class);
        Assumptions.assumeTrue(posix != null, "POSIX file permissions require a POSIX filesystem");
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ));

        assertGenericFailure(file, file);
    }

    private static void makeOwnerOnlyWhenSupported(Path file) throws Exception
    {
        PosixFileAttributeView posix =
                Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null)
        {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return;
        }

        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl != null)
        {
            UserPrincipal owner = acl.getOwner();
            acl.setAcl(List.of(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build()));
        }
    }

    private static void assertGenericFailure(Path cookieFile, Path secretPath)
    {
        YtDlpException failure = assertThrows(YtDlpException.class,
                () -> YtDlpMediaResolver.validateCookieFile(cookieFile, MediaSource.YOUTUBE));
        assertEquals(YtDlpException.Kind.CONFIGURATION, failure.getKind());
        assertFalse(failure.getMessage().contains(secretPath.toString()));
        assertFalse(failure.getMessage().contains("SESSDATA"));
    }
}
