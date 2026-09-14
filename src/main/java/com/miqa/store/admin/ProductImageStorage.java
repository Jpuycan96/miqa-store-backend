package com.miqa.store.admin;

import com.miqa.store.config.MediaProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import javax.imageio.ImageIO;

/** Only server-generated keys are managed; legacy/remote URLs never identify files to delete. */
@Service
public class ProductImageStorage {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private final MediaProperties media;
    public ProductImageStorage(MediaProperties media) { this.media = media; }
    public record Stored(String key, String url) {}

    public Stored store(String productId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new AdminFailure(400, "Selecciona una imagen no vacía");
        if (file.getSize() > MAX_BYTES) throw new AdminFailure(413, "La imagen no debe superar 5 MB");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        String type = Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);
        String ext = switch (type) {
            case "image/jpeg" -> name.endsWith(".jpg") || name.endsWith(".jpeg") ? "jpg" : "";
            case "image/png" -> name.endsWith(".png") ? "png" : "";
            case "image/webp" -> name.endsWith(".webp") ? "webp" : "";
            default -> "";
        };
        if (ext.isEmpty()) throw invalid();
        if (media.getBaseUrl().isBlank()) throw new AdminFailure(503, "La carga de imágenes aún no está configurada");
        String key = "products/" + productId + "/" + UUID.randomUUID() + "." + ext;
        Path target = null;
        boolean created = false;
        try (InputStream input = file.getInputStream()) {
            byte[] bytes = input.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new AdminFailure(413, "La imagen no debe superar 5 MB");
            validateContent(bytes, ext);
            target = managedPath(key);
            Files.createDirectories(target.getParent());
            target = managedPath(key); // Check newly created directories before writing.
            Path root = media.getStoragePath().toAbsolutePath().normalize();
            for (Path directory = target.getParent(); directory.startsWith(root); directory = directory.getParent()) {
                setPosixPermissions(directory, "rwxr-xr-x");
                if (directory.equals(root)) break; // Never change ancestors outside managed media.
            }
            try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                created = true;
                output.write(bytes);
            }
            setPosixPermissions(target, "rw-r--r--");
            return new Stored(key, media.getBaseUrl() + "/" + key);
        } catch (IOException ex) {
            if (created && target != null) deleteQuietly(key);
            throw new AdminFailure(500, "No se pudo guardar la imagen; vuelve a intentarlo");
        }
    }

    private void setPosixPermissions(Path path, String permissions) throws IOException {
        var view = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view != null) view.setPermissions(PosixFilePermissions.fromString(permissions));
    }

    private void validateContent(byte[] bytes, String ext) throws IOException {
        boolean valid = switch (ext) {
            case "jpg" -> bytes.length >= 4 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
            case "png" -> bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte)137,80,78,71,13,10,26,10});
            case "webp" -> bytes.length >= 20 && new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP")
                && Set.of("VP8 ", "VP8L", "VP8X").contains(new String(bytes, 12, 4, java.nio.charset.StandardCharsets.US_ASCII))
                && Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()) == bytes.length - 8;
            default -> false;
        };
        if (!valid) throw invalid();
        // Inspect dimensions without allocating a decoded bitmap. WebP uses its RIFF signature above.
        if (!ext.equals("webp")) {
            try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw invalid();
                var reader = readers.next();
                try {
                    reader.setInput(stream);
                    if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) throw invalid();
                } catch (IOException ex) { throw invalid(); }
                finally { reader.dispose(); }
            }
        }
    }
    private AdminFailure invalid() { return new AdminFailure(400, "Usa una imagen JPG, PNG o WebP con contenido y extensión válidos"); }

    private Path managedPath(String key) throws IOException {
        if (!key.matches("products/[A-Za-z0-9_-]{1,64}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)"))
            throw new IOException("Invalid managed key");
        Path root = media.getStoragePath().toAbsolutePath().normalize();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) throw new IOException("Outside storage");
        // Reject symlinks in all ancestors, including the configured root.
        for (Path part = target; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Symbolic link");
        }
        return target;
    }
    public void deleteQuietly(String key) {
        if (key == null) return;
        try { Files.deleteIfExists(managedPath(key)); }
        catch (IOException ex) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Managed image cleanup failed; manual storage review required");
        }
    }
}
