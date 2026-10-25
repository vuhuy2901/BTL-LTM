package com.nhom8.server.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Kho chủ đề (Topic) dùng cho trò chơi "Vẽ Hình Đoán Ý".
 * Cung cấp danh sách các từ khóa đa dạng cho người chơi vẽ và đoán.
 */
public class TopicPool {

    private static final List<String> ALL_TOPICS = new ArrayList<>();
    private static volatile List<String> builtInTopics = List.of();
    private static volatile List<String> customTopics = List.of();

    public static void setBuiltInTopics(List<String> topics) {
        builtInTopics = List.copyOf(topics);
    }

    public static void setCustomTopics(List<String> topics) {
        customTopics = List.copyOf(topics);
    }

    static {
        String[] doVat = {
            "xe đạp", "quạt trần", "bàn phím", "cái ô", "tủ lạnh", "kính lúp",
            "đồng hồ cát", "cái võng", "gương soi", "chìa khóa", "bóng đèn",
            "cái kéo", "điện thoại", "máy tính bảng", "tivi", "máy giặt",
            "nồi cơm điện", "đôi đũa", "cái muôi", "bàn ủi", "cái thớt", "cái chổi",
            "thùng rác", "cái nón", "đôi giày", "cái cặp", "lọ hoa", "cây đàn"
        };
        String[] dongVat = {
            "con mèo", "con chó", "con voi", "hươu cao cổ", "con muỗi", "cá mập",
            "con rùa", "chim cánh cụt", "con rắn", "chuồn chuồn", "con thỏ",
            "con lợn", "con báo", "con sư tử", "con hổ", "con khỉ", "con gấu",
            "con bò", "con ngựa", "con dê", "con ếch", "con gián", "con kiến",
            "ốc sên", "con nhện", "con dơi", "con mực", "con cua", "con tôm"
        };
        String[] thienNhien = {
            "cầu vồng", "núi lửa", "đám mây", "mặt trời", "ngôi sao", "mặt trăng",
            "cây cổ thụ", "hoa hướng dương", "bãi biển", "lốc xoáy", "tuyết rơi",
            "ngôi nhà", "lâu đài", "cái giếng", "cây cầu", "con thuyền", "máy bay",
            "tên lửa", "người ngoài hành tinh", "robot", "người tuyết", "hộp quà"
        };
        String[] vanHoa = {
            "trà sữa", "bánh mì", "phở", "cà phê đá", "gỏi cuốn", "nón lá",
            "xích lô", "tháp rùa", "chợ bến thành", "siêu nhân", "công chúa"
        };

        Collections.addAll(ALL_TOPICS, doVat);
        Collections.addAll(ALL_TOPICS, dongVat);
        Collections.addAll(ALL_TOPICS, thienNhien);
        Collections.addAll(ALL_TOPICS, vanHoa);
        builtInTopics = List.copyOf(ALL_TOPICS);
    }

    public static List<String> getBuiltInTopics() {
        return builtInTopics;
    }

    public static List<String> getCustomTopics() {
        return customTopics;
    }

    public static List<String> getSourceBuiltInTopics() {
        return List.copyOf(ALL_TOPICS);
    }

    public static List<List<String>> getTopicOptionsForRoom(int playerCount) {
        int requiredTopics = playerCount * 3;
        List<String> available = new ArrayList<>(builtInTopics);
        available.addAll(customTopics);
        List<String> poolCopy = new ArrayList<>(available);
        Collections.shuffle(poolCopy);
        while (poolCopy.size() < requiredTopics) {
            List<String> extra = new ArrayList<>(available);
            Collections.shuffle(extra);
            poolCopy.addAll(extra);
        }
        List<List<String>> allPlayerOptions = new ArrayList<>();
        int index = 0;
        for (int i = 0; i < playerCount; i++) {
            List<String> options = new ArrayList<>();
            options.add(poolCopy.get(index++));
            options.add(poolCopy.get(index++));
            options.add(poolCopy.get(index++));
            allPlayerOptions.add(options);
        }
        return allPlayerOptions;
    }

    public static String generateHint(String topic) {
        if (topic == null) return "";
        StringBuilder hint = new StringBuilder();
        for (char c : topic.toCharArray()) {
            if (c == ' ') { hint.append("   "); }
            else { hint.append("_ "); }
        }
        return hint.toString().trim();
    }
}
