import com.paradisemc.rokid.plugin.voicerelay.link.LinkServer;
import org.json.*;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.*;

/** Test-only phone command handler. Uses the real production socket/encryption server. */
public class LinkHarness {
    private static byte[] audio = new byte[60013];
    private static ByteArrayOutputStream upload = new ByteArrayOutputStream();
    private static Map<String, JSONObject> receipts = new HashMap<>();
    private static JSONObject settings = new JSONObject();
    private static int sends, expectedLength;
    private static String target;
    private static String hash(byte[] bytes) throws Exception { return LinkServer.hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static JSONObject execute(JSONObject q) throws Exception {
        Object value;
        switch(q.getString("op")) {
            case "inbox": value = new JSONArray().put(new JSONObject().put("id","alice").put("sender","Alice العربية").put("app","Telegram").put("text","مرحبا").put("voice",true)); break;
            case "settings": value = settings; break;
            case "setting": settings.put(q.getString("key"),q.getBoolean("enabled")); value = settings; break;
            case "status": value = new JSONObject().put("sends",sends); break;
            case "download": value = new JSONObject().put("size",audio.length).put("sha256",hash(audio)).put("mime","audio/ogg").put("download","voice-one"); break;
            case "download_chunk": {
                if (!q.getString("download").equals("voice-one")) throw new IllegalArgumentException();
                int offset = q.getInt("offset");
                value = new JSONObject().put("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(audio,offset,Math.min(audio.length,offset+24576)))); break;
            }
            case "begin": upload.reset(); target=q.getString("target"); expectedLength=q.getInt("size"); value=new JSONObject().put("upload","upload-one"); break;
            case "upload_chunk": {
                if (!q.getString("upload").equals("upload-one") || q.getInt("offset") != upload.size()) throw new IllegalArgumentException();
                upload.write(Base64.getDecoder().decode(q.getString("data"))); value=new JSONObject(); break;
            }
            case "seal":
                if (upload.size()!=expectedLength || !hash(upload.toByteArray()).equals(q.getString("sha256"))) throw new IllegalArgumentException();
                value=new JSONObject(); break;
            case "send": {
                String operation=q.getString("operation");
                if (!receipts.containsKey(operation)) { sends++; receipts.put(operation,new JSONObject().put("state","sent").put("recipient",q.getString("target")).put("detail","Sent")); }
                value=receipts.get(operation); break;
            }
            case "receipt": value=receipts.get(q.getString("operation")); break;
            case "upload_info": value=new JSONObject().put("bytes",upload.size()).put("sha256",hash(upload.toByteArray())).put("target",target); break;
            case "slow": Thread.sleep(500); value=new JSONObject(); break;
            default: return new JSONObject().put("ok",false).put("error","Unsupported test command");
        }
        return new JSONObject().put("ok",true).put("value",value);
    }
    public static void main(String[] args) throws Exception {
        for(int i=0;i<audio.length;i++) audio[i]=(byte)(i%251);
        for(String key: new String[]{"telegram_enabled","whatsapp_enabled","hide_when_phone_unlocked","respect_dnd","respect_phone_silent","nexus_notices"}) settings.put(key,true);
        LinkServer server=new LinkServer(LinkServer.unhex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),0,LinkHarness::execute,s -> {});
        server.start(); System.out.println("PORT="+server.port()); System.out.flush();
        System.in.read(); server.close();
    }
}
