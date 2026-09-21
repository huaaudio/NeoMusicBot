/* Modified by Huaaudio: migrate to JUnit Jupiter (2026). */
package io.github.huaaudio.neomusicbot.audio.media;

import org.junit.jupiter.api.Test;

import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EphemeralProcessCachePolicyTest
{
    @Test
    public void windowsAclTemplateGrantsOnlyTheOwnerFullControl()
    {
        UserPrincipal owner = () -> "runner";
        List<AclEntry> entries = EphemeralProcessCache.ownerOnlyAcl(owner);

        assertEquals(1, entries.size());
        AclEntry entry = entries.get(0);
        assertEquals(AclEntryType.ALLOW, entry.type());
        assertSame(owner, entry.principal());
        assertTrue(entry.permissions().contains(AclEntryPermission.READ_DATA));
        assertTrue(entry.permissions().contains(AclEntryPermission.WRITE_DATA));
        assertTrue(entry.permissions().contains(AclEntryPermission.WRITE_ACL));
        assertTrue(entry.permissions().contains(AclEntryPermission.WRITE_OWNER));
    }

    @Test
    public void cleanupFailureCannotEscapeResolverShutdown()
    {
        AtomicBoolean attempted = new AtomicBoolean();
        YtDlpMediaResolver.closeProcessCacheWithoutMasking(() ->
        {
            attempted.set(true);
            throw new IllegalStateException("path-and-secret-must-not-be-logged");
        });
        assertTrue(attempted.get());
    }
}
