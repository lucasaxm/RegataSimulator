package com.boatarde.regatasimulator.repository.sqlite;

import com.boatarde.regatasimulator.application.ApplicationFailure;
import com.boatarde.regatasimulator.models.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.telegram.telegrambots.meta.api.objects.Message;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

public final class SqliteCodec {
    private final ObjectMapper mapper;
    public SqliteCodec(ObjectMapper mapper) { this.mapper = mapper; }
    public String json(Object value) {
        if (value == null) return null;
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Cannot encode stored metadata", e); }
    }
    public <T> T read(String json, Class<T> type) {
        if (json == null) return null;
        try { return mapper.readValue(json, type); }
        catch (Exception e) { throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Cannot decode stored metadata", e); }
    }
    public List<TemplateArea> areas(String json) {
        try { return mapper.readValue(json, new TypeReference<List<TemplateArea>>() { }); }
        catch (Exception e) { throw new ApplicationFailure(ApplicationFailure.Kind.EXECUTION, "Cannot decode stored geometry", e); }
    }
    public void common(ResultSet row, CommonEntity item) throws SQLException {
        item.setId(UUID.fromString(row.getString("id")));
        item.setWeight(row.getInt("weight"));
        item.setStatus(Status.valueOf(row.getString("status")));
        item.setMessage(read(row.getString("message_json"), Message.class));
        item.setPreviewChatId(nullableLong(row, "preview_chat_id"));
        Long preview = nullableLong(row, "preview_message_id");
        item.setPreviewMessageId(preview == null ? null : Math.toIntExact(preview));
    }
    public static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }
    public static Integer date(Message message) { return message == null ? null : message.getDate(); }
    public static Long author(Message message) { return message == null || message.getFrom() == null ? null : message.getFrom().getId(); }
    public static Long chat(Message message) { return message == null || message.getChat() == null ? null : message.getChatId(); }
}