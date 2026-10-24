package com.nhom8.server.service;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import com.nhom8.server.model.Friendship;
import com.nhom8.server.model.User;
import com.nhom8.server.repository.FriendshipRepository;
import com.nhom8.server.repository.UserRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class FriendService {

    private final FriendshipRepository friendshipRepository;
    private final UserRepository userRepository;
    private final GameService gameService; // Để gửi thông báo WebSocket
    @org.springframework.beans.factory.annotation.Autowired
    private com.nhom8.server.repository.ChatMessageRepository chatMessageRepository;

    public FriendService(FriendshipRepository friendshipRepository, UserRepository userRepository, @Lazy GameService gameService) {
        this.friendshipRepository = friendshipRepository;
        this.userRepository = userRepository;
        this.gameService = gameService;
    }

    public void sendFriendRequest(String myUsername, String targetUsername) {
        if (myUsername.equals(targetUsername)) {
            throw new IllegalArgumentException("Không thể tự kết bạn với chính mình.");
        }

        User me = userRepository.findByUsername(myUsername);
        if (me == null) me = userRepository.findByUsernameIgnoreCase(myUsername);
        User target = userRepository.findByUsername(targetUsername);
        if (target == null) target = userRepository.findByUsernameIgnoreCase(targetUsername);
        if (me == null || target == null) {
            throw new IllegalArgumentException("Người chơi không tồn tại.");
        }

        Friendship existing = friendshipRepository.findByUsers(me, target);
        if (existing != null) {
            if (existing.getStatus() == Friendship.FriendshipStatus.ACCEPTED) {
                throw new IllegalArgumentException("Hai bạn đã là bạn bè rồi.");
            } else if (existing.getStatus() == Friendship.FriendshipStatus.PENDING) {
                throw new IllegalArgumentException("Đã có lời mời kết bạn đang chờ xử lý.");
            } else {
                existing.setSender(me);
                existing.setReceiver(target);
                existing.setStatus(Friendship.FriendshipStatus.PENDING);
                friendshipRepository.save(existing);
                gameService.notifyFriendRequestViaWS(target, me);
                return;
            }
        }

        Friendship friendship = new Friendship(me, target);
        friendshipRepository.save(friendship);
        gameService.notifyFriendRequestViaWS(target, me);
    }

    public void respondFriendRequest(String myUsername, String senderUsername, boolean isAccept) {
        User me = userRepository.findByUsername(myUsername);
        if (me == null) me = userRepository.findByUsernameIgnoreCase(myUsername);
        User sender = userRepository.findByUsername(senderUsername);
        if (sender == null) sender = userRepository.findByUsernameIgnoreCase(senderUsername);

        if (me == null || sender == null) return;

        Friendship friendship = friendshipRepository.findByUsers(me, sender);
        if (friendship != null && friendship.getStatus() == Friendship.FriendshipStatus.PENDING) {
            if (isAccept) {
                friendship.setStatus(Friendship.FriendshipStatus.ACCEPTED);
                friendshipRepository.save(friendship);
                gameService.notifyFriendAcceptViaWS(sender, me);
                gameService.sendFriendListToUser(sender.getUsername());
                gameService.sendFriendListToUser(me.getUsername());
            } else {
                friendship.setStatus(Friendship.FriendshipStatus.REJECTED);
                friendshipRepository.save(friendship);
            }
        } else {
            throw new IllegalArgumentException("Không tìm thấy lời mời kết bạn này.");
        }
    }

    public void removeFriend(String myUsername, String friendUsername) {
        User me = userRepository.findByUsername(myUsername);
        if (me == null) me = userRepository.findByUsernameIgnoreCase(myUsername);
        User friendUser = userRepository.findByUsername(friendUsername);
        if (friendUser == null) friendUser = userRepository.findByUsernameIgnoreCase(friendUsername);
        if (me == null || friendUser == null) return;

        Friendship friendship = friendshipRepository.findByUsers(me, friendUser);
        if (friendship != null && friendship.getStatus() == Friendship.FriendshipStatus.ACCEPTED) {
            friendshipRepository.delete(friendship);
            gameService.notifyFriendRemoveViaWS(friendUser, me);
            gameService.sendFriendListToUser(friendUser.getUsername());
            gameService.sendFriendListToUser(me.getUsername());
        }
    }

    public void cancelFriendRequest(String myUsername, String targetUsername) {
        User me = userRepository.findByUsernameIgnoreCase(myUsername);
        User target = userRepository.findByUsernameIgnoreCase(targetUsername);
        if (me == null || target == null) throw new IllegalArgumentException("Không tìm thấy người chơi.");
        Friendship friendship = friendshipRepository.findByUsers(me, target);
        if (friendship == null || friendship.getStatus() != Friendship.FriendshipStatus.PENDING
                || !friendship.getSender().getUserId().equals(me.getUserId())) {
            throw new IllegalArgumentException("Lời mời không còn ở trạng thái chờ hoặc không phải do bạn gửi.");
        }
        friendship.setStatus(Friendship.FriendshipStatus.REJECTED);
        friendshipRepository.save(friendship);
        gameService.notifyFriendRequestCancelled(target, me);
    }

    public List<Map<String, Object>> getFriendList(String username) {
        User user = userRepository.findByUsername(username);
        if (user == null) user = userRepository.findByUsernameIgnoreCase(username);
        if (user == null) return new ArrayList<>();

        List<Friendship> friendships = friendshipRepository.findAllAcceptedFriends(user.getUserId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Friendship f : friendships) {
            User friend;
            boolean isSenderMe = (f.getSender().getUserId() != null && f.getSender().getUserId().equals(user.getUserId()))
                    || (f.getSender().getUsername() != null && f.getSender().getUsername().equalsIgnoreCase(user.getUsername()));
            if (isSenderMe) {
                friend = f.getReceiver();
            } else {
                friend = f.getSender();
            }

            if (friend == null || friend.getUsername() == null || friend.getUsername().equalsIgnoreCase(user.getUsername())) {
                continue;
            }

            Map<String, Object> friendMap = new HashMap<>();
            friendMap.put("username", friend.getUsername());
            friendMap.put("displayName", friend.getDisplayName());
            friendMap.put("avatar", friend.getAvatar());
            friendMap.put("rankingScore", friend.getRankingScore());
            friendMap.put("isOnline", gameService.isUserOnline(friend.getUsername()));
            friendMap.put("roomId", gameService.getUserRoomId(friend.getUsername()));
            result.add(friendMap);
        }
        return result;
    }

    public List<Map<String, Object>> getPendingRequests(String username) {
        User user = userRepository.findByUsername(username);
        if (user == null) user = userRepository.findByUsernameIgnoreCase(username);
        if (user == null) return new ArrayList<>();

        List<Friendship> pending = friendshipRepository.findPendingRequestsForUser(user.getUserId());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Friendship f : pending) {
            User sender = f.getSender();
            Map<String, Object> reqMap = new HashMap<>();
            reqMap.put("username", sender.getUsername());
            reqMap.put("displayName", sender.getDisplayName());
            reqMap.put("avatar", sender.getAvatar());
            reqMap.put("rankingScore", sender.getRankingScore());
            result.add(reqMap);
        }
        return result;
    }

    public List<Map<String, Object>> searchUsers(String query, String myUsername) {
        // Tìm theo displayName (ưu tiên) và cả username
        List<User> byDisplayName = userRepository.findByDisplayNameContainingIgnoreCase(query);
        List<User> byUsername = userRepository.findByUsernameContainingIgnoreCase(query);

        // Gộp kết quả, loại bỏ trùng lặp
        java.util.LinkedHashMap<Integer, User> merged = new java.util.LinkedHashMap<>();
        for (User u : byDisplayName) merged.put(u.getUserId(), u);
        for (User u : byUsername) merged.putIfAbsent(u.getUserId(), u);

        List<Map<String, Object>> results = new ArrayList<>();
        User me = userRepository.findByUsername(myUsername);

        for (User u : merged.values()) {
            if (u.getUsername().equals(myUsername)) continue;

            Map<String, Object> uMap = new HashMap<>();
            uMap.put("username", u.getUsername());
            uMap.put("displayName", u.getDisplayName());
            uMap.put("avatar", u.getAvatar());
            uMap.put("rankingScore", u.getRankingScore());
            uMap.put("isOnline", gameService.isUserOnline(u.getUsername()));

            if (me != null) {
                Friendship f = friendshipRepository.findByUsers(me, u);
                if (f != null) {
                    uMap.put("friendStatus", f.getStatus().name());
                    if (f.getStatus() == Friendship.FriendshipStatus.PENDING) {
                        uMap.put("isSender", f.getSender().equals(me));
                    }
                } else {
                    uMap.put("friendStatus", "NONE");
                }
            }
            results.add(uMap);
        }
        return results;
    }

    public List<Map<String, Object>> getChatThreads(String myUsername) {
        User me = userRepository.findByUsername(myUsername);
        if (me == null) return new ArrayList<>();

        List<User> chattedUsers = chatMessageRepository.findChattedUsers(me);
        List<Map<String, Object>> threads = new ArrayList<>();

        for (User u : chattedUsers) {
            Map<String, Object> map = new HashMap<>();
            map.put("username", u.getUsername());
            map.put("displayName", u.getDisplayName());
            map.put("avatar", u.getAvatar());
            map.put("rankingScore", u.getRankingScore());
            map.put("isOnline", gameService.isUserOnline(u.getUsername()));
            threads.add(map);
        }
        return threads;
    }
}
