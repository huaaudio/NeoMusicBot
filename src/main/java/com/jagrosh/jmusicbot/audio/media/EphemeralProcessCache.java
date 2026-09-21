package com.jagrosh.jmusicbot.audio.media;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jagrosh.jmusicbot.audio.media.YtDlpException.Kind;

/** Private, resolver-scoped cache root erased when the resolver closes. */
final class EphemeralProcessCache implements AutoCloseable
{
    private static final String ERROR_MESSAGE = "Could not secure the temporary media resolver cache";
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private final Path directory;
    private final AtomicBoolean closed = new AtomicBoolean();

    private EphemeralProcessCache(Path directory)
    {
        this.directory = directory;
    }

    static EphemeralProcessCache create()
    {
        Path created = null;
        try
        {
            created = Files.createTempDirectory("jmusicbot-media-cache-").toAbsolutePath().normalize();
            PosixFileAttributeView posix = Files.getFileAttributeView(
                    created, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (posix != null)
            {
                Files.setPosixFilePermissions(created, OWNER_DIRECTORY_PERMISSIONS);
            }
            else
            {
                AclFileAttributeView acl = Files.getFileAttributeView(
                        created, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (acl == null)
                    throw new YtDlpException(Kind.CONFIGURATION, ERROR_MESSAGE);
                acl.setAcl(ownerOnlyAcl(acl.getOwner()));
            }
            CookieFilePermissionPolicy.validateOwnerOnly(created, ERROR_MESSAGE);
            return new EphemeralProcessCache(created);
        }
        catch (IOException | SecurityException ex)
        {
            deleteQuietly(created);
            throw new YtDlpException(Kind.CONFIGURATION, ERROR_MESSAGE, ex);
        }
        catch (YtDlpException ex)
        {
            deleteQuietly(created);
            throw ex;
        }
    }

    static List<AclEntry> ownerOnlyAcl(UserPrincipal owner)
    {
        if (owner == null)
            throw new YtDlpException(Kind.CONFIGURATION, ERROR_MESSAGE);
        return List.of(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build());
    }

    Path directory()
    {
        if (closed.get())
            throw new YtDlpException(Kind.CONFIGURATION, "The temporary media resolver cache is closed");
        return directory;
    }

    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true))
            return;
        IOException failure = null;
        for (int attempt = 0; attempt < 5; attempt++)
        {
            try
            {
                deleteRecursively(directory);
                return;
            }
            catch (IOException ex)
            {
                failure = ex;
                try
                {
                    Thread.sleep(25L << attempt);
                }
                catch (InterruptedException interrupted)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new YtDlpException(Kind.CONFIGURATION,
                "Could not erase the temporary media resolver cache", failure);
    }

    private static void deleteQuietly(Path directory)
    {
        if (directory == null)
            return;
        try
        {
            deleteRecursively(directory);
        }
        catch (IOException ignored)
        {
        }
    }

    private static void deleteRecursively(Path root) throws IOException
    {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
            return;
        Files.walkFileTree(root, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
            {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
            {
                if (failure != null)
                    throw failure;
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
