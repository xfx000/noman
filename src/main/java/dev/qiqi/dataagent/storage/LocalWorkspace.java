package dev.qiqi.dataagent.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/** Single-node storage. Caller identities always come from the server. All variable keys are hashed. */
@Component
public class LocalWorkspace {
    private final Path root;
    private final ObjectMapper json;
    public LocalWorkspace(StorageProperties properties, ObjectMapper json) {
        this.root = properties.directory().toAbsolutePath().normalize(); this.json = json;
    }
    public Path stateDirectory() { return root.resolve("agent-state"); }
    public static String key(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Storage key is required");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public Path file(String owner, String collection, String id) {
        if (!collection.matches("[a-z-]+")) throw new IllegalArgumentException("Invalid collection");
        return root.resolve("users").resolve(key(owner)).resolve(collection).resolve(key(id) + ".json");
    }
    public synchronized void write(String owner, String collection, String id, Object value) {
        Path path = file(owner, collection, id);
        try {
            Files.createDirectories(path.getParent());
            Path temp = Files.createTempFile(path.getParent(), "save-", ".tmp");
            try {
                json.writeValue(temp.toFile(), value);
                replace(temp, path);
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException e) { throw new IllegalStateException("Unable to persist workspace data", e); }
    }
    private static void replace(Path temp, Path path) throws IOException {
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | AccessDeniedException ignored) {
            // Windows often cannot ATOMIC_MOVE over an existing locked file.
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    public synchronized <T> Optional<T> read(String owner, String collection, String id, Class<T> type) {
        Path path = file(owner, collection, id);
        if (!Files.exists(path)) return Optional.empty();
        try { return Optional.of(json.readValue(path.toFile(), type)); }
        catch (IOException e) { throw new IllegalStateException("Unable to read workspace data", e); }
    }
    public synchronized void delete(String owner, String collection, String id) {
        try { Files.deleteIfExists(file(owner, collection, id)); }
        catch (IOException e) { throw new IllegalStateException("Unable to delete workspace data", e); }
    }
}
