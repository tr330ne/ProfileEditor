package treeone.profileeditor;

import com.zenith.network.client.Authenticator;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.OBJECT_MAPPER;
import static com.zenith.util.config.Config.Authentication.AccountType.OFFLINE;

public final class MinecraftServices {
    private static final String BASE = "https://api.minecraftservices.com";

    public record ApiResult(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    public static boolean isOfflineAccount() {
        return CONFIG.authentication.accountType == OFFLINE;
    }

    public static Optional<String> accessToken() {
        return Authenticator.INSTANCE.loadAuthCache()
            .map(manager -> manager.getMinecraftToken().getUpToDateUnchecked().getToken());
    }

    public static Optional<byte[]> downloadPng(String url) {
        try (var client = newClient()) {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            var code = response.statusCode();
            if (code >= 200 && code < 300) {
                return Optional.of(response.body());
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    public static boolean refreshProfileAndSave() {
        var managerOpt = Authenticator.INSTANCE.loadAuthCache();
        if (managerOpt.isEmpty()) return false;
        try {
            var manager = managerOpt.get();
            manager.getMinecraftProfile().refresh();
            Authenticator.INSTANCE.updateConfig(manager);
            Authenticator.INSTANCE.saveAuthCache(manager);
            return true;
        } catch (Exception e) {
            ProfileEditorPlugin.LOG.warn("Failed to refresh profile into config after name change", e);
            return false;
        }
    }

    public record NameChangeInfo(boolean allowed, String changedAt) {}

    public static Optional<NameChangeInfo> nameChangeInfo(String token) {
        return getJson(token, "/minecraft/profile/namechange", node ->
            new NameChangeInfo(node.path("nameChangeAllowed").asBoolean(false), nodeText(node.get("changedAt"))));
    }

    public static Optional<String> nameAvailability(String token, String name) {
        return getJson(token, "/minecraft/profile/name/" + name + "/available", node -> nodeText(node.get("status")));
    }

    public record Cape(String id, String alias, boolean active) {}

    public record ProfileInfo(String name, String skinUrl, String skinVariant, List<Cape> capes) {}

    public static Optional<ProfileInfo> getProfile(String token) {
        return getJson(token, "/minecraft/profile", node -> {
            var name = nodeText(node.get("name"));
            String skinUrl = null;
            String skinVariant = null;
            var skins = node.get("skins");
            if (skins != null) {
                for (var skin : skins) {
                    if ("ACTIVE".equalsIgnoreCase(nodeText(skin.get("state")))) {
                        skinUrl = nodeText(skin.get("url"));
                        skinVariant = nodeText(skin.get("variant"));
                        break;
                    }
                }
            }
            var capes = new ArrayList<Cape>();
            var capesNode = node.get("capes");
            if (capesNode != null) {
                for (var cape : capesNode) {
                    capes.add(new Cape(
                        nodeText(cape.get("id")),
                        nodeText(cape.get("alias")),
                        "ACTIVE".equalsIgnoreCase(nodeText(cape.get("state")))));
                }
            }
            return new ProfileInfo(name, skinUrl, skinVariant, capes);
        });
    }

    private static <T> Optional<T> getJson(String token, String path, Function<JsonNode, T> parser) {
        var result = sendGet(token, path);
        if (!result.ok()) return Optional.empty();
        try {
            return Optional.ofNullable(parser.apply(OBJECT_MAPPER.readTree(result.body())));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static ApiResult setCape(String token, String capeId) {
        return sendJson(token, "/minecraft/profile/capes/active", "PUT",
            OBJECT_MAPPER.writeValueAsString(Map.of("capeId", capeId)));
    }

    public static ApiResult removeCape(String token) {
        return send(req(token, "/minecraft/profile/capes/active").DELETE().build());
    }

    public static ApiResult changeName(String token, String newName) {
        return send(req(token, "/minecraft/profile/name/" + newName).PUT(HttpRequest.BodyPublishers.noBody()).build());
    }

    public static ApiResult setSkinFromUrl(String token, String url, String variant) {
        return sendJson(token, "/minecraft/profile/skins", "POST",
            OBJECT_MAPPER.writeValueAsString(Map.of("variant", variant, "url", url)));
    }

    public static ApiResult setSkinFromFile(String token, byte[] png, String variant) {
        var boundary = "ZenithProfileEditor" + Long.toHexString(System.nanoTime());
        var request = req(token, "/minecraft/profile/skins")
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody(boundary, variant, png)))
            .build();
        return send(request);
    }

    private static String nodeText(JsonNode node) {
        return node != null && !node.isNull() ? node.asString() : null;
    }

    private static ApiResult sendGet(String token, String path) {
        return send(req(token, path).GET().build());
    }

    private static ApiResult sendJson(String token, String path, String method, String json) {
        return send(req(token, path)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build());
    }

    private static HttpRequest.Builder req(String token, String path) {
        return HttpRequest.newBuilder()
            .uri(URI.create(BASE + path))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .header("User-Agent", "ZenithProxy-ProfileEditor")
            .timeout(Duration.ofSeconds(30));
    }

    private static ApiResult send(HttpRequest request) {
        try (var client = newClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new ApiResult(response.statusCode(), response.body());
        } catch (Exception e) {
            return new ApiResult(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    private static byte[] multipartBody(String boundary, String variant, byte[] png) {
        var out = new ByteArrayOutputStream();
        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"variant\"\r\n\r\n");
        writeAscii(out, variant + "\r\n");
        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n");
        writeAscii(out, "Content-Type: image/png\r\n\r\n");
        out.writeBytes(png);
        writeAscii(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
