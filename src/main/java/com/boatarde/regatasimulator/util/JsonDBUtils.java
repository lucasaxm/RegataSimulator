package com.boatarde.regatasimulator.util;

import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.Comparator;
import java.util.Optional;

@Slf4j
@UtilityClass
public class JsonDBUtils {
    public static String getJxQuery(Status status, Long userId) {
        StringBuilder jxQueryBuilder = new StringBuilder("/.");
        if (status == null && userId == null) {
            return jxQueryBuilder.toString();
        }
        if (status != null) {
            jxQueryBuilder.append("[status='").append(status).append("'");
        } else {
            jxQueryBuilder.append("[message/from/id=").append(userId).append("]");
            return jxQueryBuilder.toString();
        }
        if (userId != null) {
            jxQueryBuilder.append(" and message/from/id=").append(userId);
        }
        jxQueryBuilder.append("]");
        return jxQueryBuilder.toString();
    }

    public static Comparator<Template> getTemplateComparator() {
        return Comparator.comparing(
            template -> Optional.ofNullable(template.getMessage()).map(Message::getDate).orElse(0));
    }

    public static Comparator<Source> getSourceComparator() {
        return Comparator.comparing(
            source -> Optional.ofNullable(source.getMessage()).map(Message::getDate).orElse(0));
    }
}
