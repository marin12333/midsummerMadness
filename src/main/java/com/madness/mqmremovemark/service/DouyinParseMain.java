package com.madness.mqmremovemark.service;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
public class DouyinParseMain {
    // 使用最新的 Chrome 120 UA
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build();
    private static final ObjectMapper mapper = new ObjectMapper();
    public static void main(String[] args) {
        // 测试链接
        String rawInput = "https://v.douyin.com/JN3ufTigbDY/";
        try {
            String targetUrl = extractUrl(rawInput);
            if (targetUrl == null) { System.err.println("错误：未发现链接"); return; }
            System.out.println(">>> 提取到的链接: " + targetUrl);
            String realUrl = getRealUrl(targetUrl);
            System.out.println(">>> 真实URL: " + realUrl);
            String awemeId = extractAwemeId(realUrl);
            if (awemeId == null) { System.err.println("错误：未提取视频ID"); return; }
            System.out.println(">>> 视频ID: " + awemeId);
            // 【重点】这里使用了加强版的 fetchHtml
            String html = fetchHtml("https://www.douyin.com/video/" + awemeId);
            // 如果返回的HTML非常短，说明还是被拦截了
            if (html.length() < 1000) {
                System.err.println(">>> 警告：获取到的HTML内容过短，可能依然被拦截。");
                System.err.println(">>> HTML内容: " + html);
            }
            JsonNode dataNode = parseRenderDataV2(html);
            if (dataNode == null) {
                System.err.println("\n\n>>> 解析失败。");
                System.err.println(">>> 建议：请确认你的 Cookie 是刚刚从浏览器刷新页面后复制的。");
                return;
            }
            extractAndPrintMediaInfo(dataNode);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // ==========================================
    // 【核心修复】加强版请求头，模拟真实 Chrome
    // ==========================================
    private static String fetchHtml(String url) throws IOException {
        // 【重要】务必粘贴刚刚刷新页面后复制的 Cookie
        String YOUR_COOKIES = "enter_pc_once=1; UIFID_TEMP=5bdad390e71fd6e6e69e3cafe6018169c2447c8bc0b8484cc0f203a274f99fdbb53b4a5f2433e2baff4ed6cbbb7990c3638561f0b9754c981fd893c22d5edafb02b80552539cfa7736bc8bbea3cdf44e; hevc_supported=true; fpk1=U2FsdGVkX19i6tzGM8c5LVrwQZRF7HJhKgL7SFZ+rfa9CwOhnXxtTXLZwh/l+JaXKIy0qUSghQmM+PzsVsDajA==; fpk2=89db729cfcdc129111f017b0e7ac324a; xgplayer_device_id=9377760284; xgplayer_user_id=817897683305; __live_version__=%221.1.4.6370%22; live_use_vvc=%22false%22; d_ticket=1770b8cf0b9d7ff674a20db04ea30993c87fa; UIFID=5bdad390e71fd6e6e69e3cafe6018169c2447c8bc0b8484cc0f203a274f99fdbef8dea97223f9482f28197a3ab191d926ae61bca44721b66047dcdb1ce3c1af9ab0b395846228d0412edcf502ef617449626b41a8ad05addd3d8683af9d63b42850dc165d8d9a461d5dc56170b162a3b4d2366e2adc6f4a557cda36428c4210117d1a83f8eab60301937d1e66a8b70a08b17bbe9b7fd8f7ef53f1debb9768b70; s_v_web_id=verify_mnkb1jwy_k29skzge_2Lih_4tMn_Btug_imfZWAQ1OhFd; passport_csrf_token=33d4f1d3e3733b5212c1387dd260144f; passport_csrf_token_default=33d4f1d3e3733b5212c1387dd260144f; bd_ticket_guard_client_web_domain=2; douyin.com; device_web_cpu_core=12; device_web_memory_size=16; architecture=amd64; is_support_rtm_web_ts=1; dy_swidth=1920; dy_sheight=1080; is_dash_user=1; passport_assist_user=CkCOxqkWTywve4GRd_XkVjAS5LrUb5D5ei_iYvFomAQroRRp6_xKcHJfmMl0wkVHK6AVOrN-TCFV0uYX3Haarbh_GkoKPAAAAAAAAAAAAABQYs2V-AW-rx2lex_SC3M2LMcrVolMe0Mh56Cc0xRyUJsdXF-oP4BMxinYS9_86RIcERCD0pAOGImv1lQgASIBA8pjUbo%3D; n_mh=rUWTI1-NnKPbGIGKC0av1LBlXOa7lOmMpDEa243UCoI; sid_guard=f20326f1147c3441e140e484d17c3134%7C1777986778%7C5184000%7CSat%2C+04-Jul-2026+13%3A12%3A58+GMT; uid_tt=bd91adb3676a7566365482febc453c5a; uid_tt_ss=bd91adb3676a7566365482febc453c5a; sid_tt=f20326f1147c3441e140e484d17c3134; sessionid=f20326f1147c3441e140e484d17c3134; sessionid_ss=f20326f1147c3441e140e484d17c3134; session_tlb_tag=sttt%7C7%7C8gMm8RR8NEHhQOSE0XwxNP_________3rVKVo-iHNZ_lEICu3t0zT8czNWLOe_Ukr_xqO14NXAI%3D; is_staff_user=false; has_biz_token=false; sid_ucp_v1=1.0.0-KGViYWM4YjRmY2FhZDkxOGQzZjhiNGRhM2MwZTUyNTlhNWI5NTg0MTgKIQik7fDT5c2SARDa2efPBhjvMSAMMNzOlq8GOAdA9AdIBBoCbHEiIGYyMDMyNmYxMTQ3YzM0NDFlMTQwZTQ4NGQxN2MzMTM0; ssid_ucp_v1=1.0.0-KGViYWM4YjRmY2FhZDkxOGQzZjhiNGRhM2MwZTUyNTlhNWI5NTg0MTgKIQik7fDT5c2SARDa2efPBhjvMSAMMNzOlq8GOAdA9AdIBBoCbHEiIGYyMDMyNmYxMTQ3YzM0NDFlMTQwZTQ4NGQxN2MzMTM0; _bd_ticket_crypt_cookie=c04fba993d273b10988c92aa8b3606c1; __security_server_data_status=1; login_time=1777986779047; publish_badge_show_info=%220%2C0%2C0%2C1777986779471%22; DiscoverFeedExposedAd=%7B%7D; PhoneResumeUidCacheV1=%7B%22644787778369188%22%3A%7B%22time%22%3A1777986781275%2C%22noClick%22%3A1%7D%7D; __druidClientInfo=JTdCJTIyY2xpZW50V2lkdGglMjIlM0E1NDklMkMlMjJjbGllbnRIZWlnaHQlMjIlM0E4OTYlMkMlMjJ3aWR0aCUyMiUzQTU0OSUyQyUyMmhlaWdodCUyMiUzQTg5NiUyQyUyMmRldmljZVBpeGVsUmF0aW8lMjIlM0ExJTJDJTIydXNlckFnZW50JTIyJTNBJTIyTW96aWxsYSUyRjUuMCUyMChXaW5kb3dzJTIwTlQlMjAxMC4wJTNCJTIwV2luNjQlM0IlMjB4NjQpJTIwQXBwbGVXZWJLaXQlMkY1MzcuMzYlMjAoS0hUTUwlMkMlMjBsaWtlJTIwR2Vja28pJTIwQ2hyb21lJTJGMTQ3LjAuMC4wJTIwU2FmYXJpJTJGNTM3LjM2JTIyJTdE; strategyABtestKey=%221778068068.299%22; ttwid=1%7CxkFRyVGarQL02zSAzblSefXkMpBfHshny4he0pU3EzM%7C1778068068%7Cb0d5fd9ef85d0847f680c85301bb170befbdc5fe8be5977ba393bce35194725b; download_guide=%223%2F20260506%2F0%22; __ac_signature=_02B4Z6wo00f01FTqGtAAAIDDC38RHZShrARUyh5AAHzJ2d; stream_player_status_params=%22%7B%5C%22is_auto_play%5C%22%3A0%2C%5C%22is_full_screen%5C%22%3A0%2C%5C%22is_full_webscreen%5C%22%3A0%2C%5C%22is_mute%5C%22%3A1%2C%5C%22is_speed%5C%22%3A1%2C%5C%22is_visible%5C%22%3A0%7D%22; playRecommendGuideTagCount=3; totalRecommendGuideTagCount=3; stream_recommend_feed_params=%22%7B%5C%22cookie_enabled%5C%22%3Atrue%2C%5C%22screen_width%5C%22%3A1920%2C%5C%22screen_height%5C%22%3A1080%2C%5C%22browser_online%5C%22%3Atrue%2C%5C%22cpu_core_num%5C%22%3A12%2C%5C%22device_memory%5C%22%3A16%2C%5C%22downlink%5C%22%3A10%2C%5C%22effective_type%5C%22%3A%5C%224g%5C%22%2C%5C%22round_trip_time%5C%22%3A0%7D%22; SelfTabRedDotControl=%5B%7B%22id%22%3A%227619702607342209062%22%2C%22u%22%3A43%2C%22c%22%3A0%7D%2C%7B%22id%22%3A%227578225099212654628%22%2C%22u%22%3A47%2C%22c%22%3A0%7D%2C%7B%22id%22%3A%227221404813307676733%22%2C%22u%22%3A203%2C%22c%22%3A0%7D%2C%7B%22id%22%3A%227595104443150895138%22%2C%22u%22%3A73%2C%22c%22%3A0%7D%2C%7B%22id%22%3A%227436952060911683638%22%2C%22u%22%3A85%2C%22c%22%3A0%7D%2C%7B%22id%22%3A%227605508659879086122%22%2C%22u%22%3A79%2C%22c%22%3A0%7D%2C%7B%22id%22%3A%227615147024463988736%22%2C%22u%22%3A37%2C%22c%22%3A0%7D%2C%7B%22id%22%3A%227624106140871165967%22%2C%22u%22%3A33%2C%22c%22%3A0%7D%2C%7B%22id%22%3A%227618542146778892351%22%2C%22u%22%3A40%2C%22c%22%3A0%7D%2C%7B%22id%22%3A%227211212127766317096%22%2C%22u%22%3A89%2C%22c%22%3A0%7D%2C%7B%22id%22%3A%227593919234103756827%22%2C%22u%22%3A61%2C%22c%22%3A0%7D%2C%7B%22id%22%3A%227606451611765835795%22%2C%22u%22%3A22%2C%22c%22%3A0%7D%2C%7B%22id%22%3A%227623983149575309338%22%2C%22u%22%3A2%2C%22c%22%3A0%7D%5D; FOLLOW_LIVE_POINT_INFO=%22MS4wLjABAAAAiY1mgqAiC3RJLiwg79lyQAgWJ1icUNpvvSSwoN2KRQw%2F1778083200000%2F0%2F0%2F1778082301896%22; FOLLOW_NUMBER_YELLOW_POINT_INFO=%22MS4wLjABAAAAiY1mgqAiC3RJLiwg79lyQAgWJ1icUNpvvSSwoN2KRQw%2F1778083200000%2F0%2F0%2F1778082901896%22; bd_ticket_guard_client_data=eyJiZC10aWNrZXQtZ3VhcmQtdmVyc2lvbiI6MiwiYmQtdGlja2V0LWd1YXJkLWl0ZXJhdGlvbi12ZXJzaW9uIjoxLCJiZC10aWNrZXQtZ3VhcmQtcmVlLXB1YmxpYy1rZXkiOiJCUHFDcTk0c1lvMUtQcndOSnFSOGVvTFM0VGQ0T0lQOHJZTDkxajM3d1VJKzl5MEpSdTlhNjErYkphTE0yTDJ2d2kzT2xzcjFiSEJDaWk4cnVaZjNEN1E9IiwiYmQtdGlja2V0LWd1YXJkLXdlYi12ZXJzaW9uIjoyfQ%3D%3D; home_can_add_dy_2_desktop=%221%22; volume_info=%7B%22isUserMute%22%3Afalse%2C%22isMute%22%3Atrue%2C%22volume%22%3A0.255%7D; odin_tt=bc4eaab84bad9be6416a4fee0a189d16163e1d028a301bd555122cbed233636cd922019ab4fe2cb20459aa6df7fddffae527746ee0b9a93cc3c1c0924c8f560f; biz_trace_id=6e5de1c6; gulu_source_res=eyJwX2luIjoiNzBkYjdhNGExYTY1YmE2OGQ2ZWFlY2Q5MDJmODJiOTRjOTFjZjU4ZTEyZTZlN2VlNTk4OGM2YzllMWNiOWE0NSJ9; sdk_source_info=7e276470716a68645a606960273f276364697660272927676c715a6d6069756077273f276364697660272927666d776a68605a607d71606b766c6a6b5a7666776c7571273f275e58272927666a6b766a69605a696c6061273f27636469766027292762696a6764695a7364776c6467696076273f275e582729277672715a646971273f2763646976602729277f6b5a666475273f2763646976602729276d6a6e5a6b6a716c273f2763646976602729276c6b6f5a7f6367273f27636469766027292771273f27313d3c3c3d34373d353d323234272927676c715a75776a716a666a69273f2763646976602778; bit_env=bilC8_zIWXrwSfE8gtvDjYfG0z-_69_7qheEJ9fwyAY4QJMfMFxI09oa8BUaDSz2-jTZW1UN2OY19j0iLuUmXT71fRemH9rn7FjrWWfNFG7BIH2tBzdNp2AxJCyIFVBa8YRnTW8jq3tAQxG_szxB-oyl8wMD4jRVJE9ptEi70gwnCK8MNbJMmonB-I0gmUHjgs1nO-hMPb4dw_iJWX6D0twOEbia1PErFYZnTdK45UMR-kqTGzb4Z-_5ehpmRfRjqBvH-OIBi3lAk2leRrjIKqN9LvMVan6jAZCAqSObRZb-WgFLxpJ-HIGESCDPRFhisnlkq5vwQMce701z3xlzwLnRMqNWIWAO7oa3uM-c3XBcgZ4ZQ9VgXEW8dCSpYItOh8OD6IjWefdbV66nfFxt4opx27gujWzVhH4sfc1TYeNfgPv8Pomqacef2DfpOyKGcvrRTOUQGrGaRgnlqK0uGM9abIeBeHC9QxU1rOgZcsqiikuuDAg1nR7RB41uWl_s; bd_ticket_guard_client_data_v2=eyJyZWVfcHVibGljX2tleSI6IkJQcUNxOTRzWW8xS1Byd05KcVI4ZW9MUzRUZDRPSVA4cllMOTFqMzd3VUkrOXkwSlJ1OWE2MStiSmFMTTJMMnZ3aTNPbHNyMWJIQkNpaThydVpmM0Q3UT0iLCJ0c19zaWduIjoidHMuMi4xZTM1MDRlMGZiZTZlM2NlODAyYjgyMzE5NTg3NzE5YTQ3NjJiYjlmOWExMWY2YWM2MTNkNDBmMTE1MmRhYWE5YzRmYmU4N2QyMzE5Y2YwNTMxODYyNGNlZGExNDkxMWNhNDA2ZGVkYmViZWRkYjJlMzBmY2U4ZDRmYTAyNTc1ZCIsInJlcV9jb250ZW50Ijoic2VjX3RzIiwicmVxX3NpZ24iOiJteEVWNmM0dFRVVjBFc1Z3TVBMOFA5UzRuNE4zQy8xaHF3YXd6MCtuL0pnPSIsInNlY190cyI6IiNPYjd3dStBNTlxRlFSSHNJS2Q5cllsM0pIQThPWnY2ZkJITUladS9PaXBiNHJNZzJNb2JvVGE1OFFyUksifQ%3D%3D; __security_mc_1_s_sdk_crypt_sdk=48b758e6-4a44-a66b; __security_mc_1_s_sdk_cert_key=8ee9685b-4f5a-b7f9; __security_mc_1_s_sdk_sign_data_key_web_protect=5e89c422-4eb1-ba54; IsDouyinActive=false";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Cookie", YOUR_COOKIES.trim())
                .header("Referer", "https://www.douyin.com/")
                // --- 以下是伪装真实浏览器的关键头 ---
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Accept-Encoding", "gzip, deflate, br")
                // Chrome 120 的指纹特征
                .header("sec-ch-ua", "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"")
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Windows\"")
                // 导航行为特征
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none") // 直接输入URL访问通常是 none
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("请求失败: " + response);
            return response.body().string();
        }
    }
    // --- 以下解析逻辑保持不变 ---
    private static JsonNode parseRenderDataV2(String html) throws Exception {
        Document doc = Jsoup.parse(html);
        Element scriptTag = doc.selectFirst("script#RENDER_DATA");
        if (scriptTag != null) return decodeAndParse(scriptTag.data());
        scriptTag = doc.selectFirst("script#__NUXT__");
        if (scriptTag != null) {
            try { return mapper.readTree(scriptTag.data()); } catch (Exception e) {}
        }
        System.out.println(">>> 提示：未找到标准ID，开始遍历所有Script标签...");
        Elements scripts = doc.select("script");
        for (Element script : scripts) {
            String content = script.data();
            if (content.length() > 100 && (content.contains("aweme_id") || content.contains("videoDetailData") || content.contains("desc"))) {
                return decodeAndParse(content);
            }
        }
        System.err.println(">>> HTML调试标题: " + doc.title());
        return null;
    }
    private static JsonNode decodeAndParse(String data) throws Exception {
        try {
            return mapper.readTree(URLDecoder.decode(data, StandardCharsets.UTF_8.name()));
        } catch (Exception e) {
            return mapper.readTree(data);
        }
    }
    private static void extractAndPrintMediaInfo(JsonNode root) {
        try {
            JsonNode videoDetail = findVideoDataNode(root);
            if (videoDetail == null) { System.err.println(">>> 错误：未能找到视频数据节点"); return; }
            String desc = videoDetail.path("desc").asText();
            String author = videoDetail.path("author").path("nickname").asText();
            System.out.println("\n================ 解析成功 ================");
            System.out.println("标题: " + desc);
            System.out.println("作者: " + author);
            JsonNode imagesNode = videoDetail.path("images");
            if (imagesNode != null && imagesNode.isArray() && imagesNode.size() > 0) {
                System.out.println("类型: 图集");
                for (int i = 0; i < imagesNode.size(); i++) {
                    JsonNode img = imagesNode.get(i);
                    String url = img.path("url_list").get(0).asText();
                    System.out.println("图片 " + (i + 1) + ": " + url);
                    if (img.has("live_photo") && !img.path("live_photo").path("url_list").isEmpty()) {
                        System.out.println("    └─ Live Photo(动图): " + img.path("live_photo").path("url_list").get(0).asText());
                    }
                }
            } else {
                System.out.println("类型: 视频");
                JsonNode videoNode = videoDetail.path("video");
                String playAddr = videoNode.path("play_addr").path("url_list").get(0).asText();
                System.out.println("视频(无水印): " + playAddr.replace("playwm", "play"));
                System.out.println("封面: " + videoNode.path("cover").path("url_list").get(0).asText());
                if (videoNode.has("dynamic_cover") && !videoNode.path("dynamic_cover").path("url_list").isEmpty()) {
                    System.out.println("Live Photo(动态封面): " + videoNode.path("dynamic_cover").path("url_list").get(0).asText());
                } else {
                    System.out.println("Live Photo: 未找到");
                }
            }
            System.out.println("==========================================");
        } catch (Exception e) { e.printStackTrace(); }
    }
    private static JsonNode findVideoDataNode(JsonNode node) {
        if (node.has("aweme_id")) return node;
        if (node.isObject()) {
            if (node.has("app")) {
                JsonNode app = node.get("app");
                if (app.has("videoDetailData")) return app.get("videoDetailData");
                if (app.has("videoData")) return app.get("videoData");
            }
            Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String key = fieldNames.next();
                JsonNode result = findVideoDataNode(node.get(key));
                if (result != null) return result;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode result = findVideoDataNode(child);
                if (result != null) return result;
            }
        }
        return null;
    }
    private static String extractUrl(String text) {
        Pattern pattern = Pattern.compile("(https?://\\S+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) return matcher.group(1).replaceAll("[。！？，]$", "");
        return null;
    }
    private static String getRealUrl(String url) throws IOException {
        Request request = new Request.Builder().url(url).head().addHeader("User-Agent", USER_AGENT).build();
        try (Response response = client.newCall(request).execute()) {
            return response.request().url().toString();
        }
    }
    private static String extractAwemeId(String url) {
        Pattern pattern = Pattern.compile("(video|note)/([0-9]+)");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) return matcher.group(2);
        return null;
    }
}