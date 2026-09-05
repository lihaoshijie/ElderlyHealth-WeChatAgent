package com.Myself.demo.mapper;

import com.Myself.demo.entity.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface AdminMapper {

    @Select("SELECT * FROM admin_user_info ORDER BY last_msg_time DESC")
    List<UserInfo> listUsers();

    @Select("SELECT * FROM admin_user_info WHERE from_user_id = #{userId}")
    UserInfo getUser(@Param("userId") String userId);

    @Insert("INSERT INTO admin_user_info (from_user_id, first_msg_time, last_msg_time, msg_count) " +
            "VALUES (#{fromUserId}, NOW(), NOW(), 1) " +
            "ON DUPLICATE KEY UPDATE last_msg_time = NOW(), msg_count = msg_count + 1")
    void upsertUser(@Param("fromUserId") String fromUserId);

    @Update("UPDATE admin_user_info SET nickname = #{nickname} WHERE from_user_id = #{userId}")
    void updateNickname(@Param("userId") String userId, @Param("nickname") String nickname);

    // ===== 统一消息查询：按 msgType 动态 JOIN 单一资源表 =====
    @Select("SELECT m.id, m.from_user_id, m.role, m.msg_type, m.direction, m.msg_id, m.content, m.created_at, " +
            "COALESCE(img.disk_path, f.disk_path, v.disk_path) AS resource_url, " +
            "COALESCE(img.recognized_desc, f.content_text, v.transcript) AS resource_extra " +
            "FROM admin_user_messages m " +
            "LEFT JOIN admin_user_images img ON m.msg_type = 'image' AND m.msg_id = img.id " +
            "LEFT JOIN admin_user_files  f   ON m.msg_type = 'file'  AND m.msg_id = f.id " +
            "LEFT JOIN admin_user_voices v   ON m.msg_type = 'voice' AND m.msg_id = v.id " +
            "WHERE m.from_user_id = #{userId} " +
            "ORDER BY m.created_at ASC LIMIT #{offset}, #{limit}")
    @Results({
        @Result(property = "msgType", column = "msg_type"),
        @Result(property = "msgId", column = "msg_id"),
        @Result(property = "resourceUrl", column = "resource_url"),
        @Result(property = "resourceExtra", column = "resource_extra")
    })
    List<UserMessage> getMessagesUnified(String userId, int offset, int limit);

    // ===== 兼容旧版单表查询（存量消息无 msgType 时使用）=====
    @Select("SELECT * FROM admin_user_messages WHERE from_user_id = #{userId} ORDER BY created_at DESC LIMIT #{offset}, #{limit}")
    List<UserMessage> getMessages(String userId, int offset, int limit);

    // ===== 插入消息主表（返回自增ID） =====
    @Insert("INSERT INTO admin_user_messages (from_user_id, role, msg_type, direction, msg_id, content, created_at) " +
            "VALUES (#{fromUserId}, #{role}, #{msgType}, #{direction}, #{msgId}, #{content}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertMsg(UserMessage msg);


    // ===== 插入图片资源（返回自增ID） =====
    @Insert("INSERT INTO admin_user_images (msg_id, from_user_id, disk_path, recognized_desc) " +
            "VALUES (#{msgId}, #{fromUserId}, #{diskPath}, #{recognizedDesc})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertImage(UserImage image);

    @Insert("INSERT INTO admin_user_images (from_user_id, disk_path, recognized_desc) VALUES (#{userId}, #{path}, #{desc})")
    void insertImageLegacy(String userId, String path, String desc);

    // ===== 插入文件资源（返回自增ID） =====
    @Insert("INSERT INTO admin_user_files (msg_id, from_user_id, file_name, disk_path, content_text) " +
            "VALUES (#{msgId}, #{fromUserId}, #{fileName}, #{diskPath}, #{contentText})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertFile(UserFile file);

    @Insert("INSERT INTO admin_user_files (from_user_id, file_name, disk_path, content_text) VALUES (#{userId}, #{name}, #{path}, #{text})")
    void insertFileLegacy(String userId, String name, String path, String text);

    // ===== 插入语音资源（返回自增ID） =====
    @Insert("INSERT INTO admin_user_voices (msg_id, from_user_id, disk_path, duration, transcript) " +
            "VALUES (#{msgId}, #{fromUserId}, #{diskPath}, #{duration}, #{transcript})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertVoice(UserVoice voice);

    // ===== 批量删除消息 + 联动清理资源表 =====
    @Delete("<script>DELETE FROM admin_user_images WHERE msg_id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteImagesByMsgIds(@Param("ids") List<Long> msgIds);

    @Delete("<script>DELETE FROM admin_user_files WHERE msg_id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteFilesByMsgIds(@Param("ids") List<Long> msgIds);

    @Delete("<script>DELETE FROM admin_user_voices WHERE msg_id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteVoicesByMsgIds(@Param("ids") List<Long> msgIds);

    @Delete("<script>DELETE FROM admin_user_messages WHERE from_user_id = #{userId} AND id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteMessagesBatch(@Param("userId") String userId, @Param("ids") List<Long> ids);

    // ===== 按资源ID批量删除（admin 后台图片/文件批量操作） =====
    @Delete("<script>DELETE FROM admin_user_images WHERE id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteImagesBatch(@Param("ids") List<Long> ids);

    @Delete("<script>DELETE FROM admin_user_files WHERE id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int deleteFilesBatch(@Param("ids") List<Long> ids);

    // ===== 清空 =====
    @Delete("DELETE FROM admin_user_messages WHERE from_user_id = #{userId}")
    void deleteMessages(@Param("userId") String userId);

    @Delete("DELETE FROM admin_user_images WHERE from_user_id = #{userId}")
    void deleteImages(@Param("userId") String userId);

    @Delete("DELETE FROM admin_user_files WHERE from_user_id = #{userId}")
    void deleteFiles(@Param("userId") String userId);

    @Delete("DELETE FROM admin_user_info WHERE from_user_id = #{userId}")
    void deleteUser(@Param("userId") String userId);


    @Insert("INSERT INTO admin_user_messages (from_user_id, role, content) VALUES (#{userId}, #{role}, #{content})")
    void insertMessage(@Param("userId") String userId, @Param("role") String role, @Param("content") String content);

    @Select("SELECT * FROM admin_user_images WHERE from_user_id = #{userId} ORDER BY created_at DESC")
    List<UserImage> getImages(@Param("userId") String userId);

    @Select("SELECT * FROM admin_user_files WHERE from_user_id = #{userId} ORDER BY created_at DESC")
    List<UserFile> getFiles(@Param("userId") String userId);

    // ===== 审计/追踪 =====
    @Insert("INSERT INTO agent_traces (user_id, task_id, round_num, tool_name, tool_args, tool_result, status, retry_count) " +
            "VALUES (#{userId}, #{taskId}, #{round}, #{toolName}, #{toolArgs}, #{toolResult}, #{status}, #{retryCount})")
    void insertTrace(String userId, String taskId, int round, String toolName, String toolArgs, String toolResult, String status, int retryCount);

    @Select("SELECT password FROM admin_user WHERE username = #{username}")
    String getAdminPassword(@Param("username") String username);

    @Select("SELECT EXISTS(SELECT 1 FROM admin_user_info WHERE from_user_id = #{userId})")
    boolean userExists(@Param("userId") String userId);
}
