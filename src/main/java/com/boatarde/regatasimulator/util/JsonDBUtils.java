package com.boatarde.regatasimulator.util;

import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.models.Meme;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    public static Comparator<CommonEntity> getComparator() {
        return Comparator.comparing(
            template -> Optional.ofNullable(template.getMessage()).map(Message::getDate).orElse(0));
    }

    public static Comparator<Meme> getMemeComparator() {
        return Comparator.comparing(
            template -> Optional.ofNullable(template.getMessage()).map(Message::getDate).orElse(0));
    }

    public static List<Source> selectSourcesWithWeight(List<Source> sources, int amount) {
        return selectWithWeight(sources, amount).stream()
            .filter(Source.class::isInstance)
            .map(Source.class::cast)
            .collect(Collectors.toList());
    }

    public static List<Template> selectTemplatesWithWeight(List<Template> templates, int amount) {
        return selectWithWeight(templates, amount).stream()
            .filter(Template.class::isInstance)
            .map(Template.class::cast)
            .collect(Collectors.toList());
    }

    private static List<? extends CommonEntity> selectWithWeight(List<? extends CommonEntity> entities, int amount) {
        if (entities.size() < amount) {
            throw new RuntimeException(
                "Not enough entities to select from. Amount requested: %d, entities available: %d".formatted(amount,
                    entities.size()));
        }
        List<CommonEntity> selectedEntities = new ArrayList<>(); // List to store selected entities
        Random random = new Random(); // Random object for generating random numbers

        for (int j = 0; j < amount && !entities.isEmpty(); j++) {
            int[] cumulativeWeights = new int[entities.size()]; // Array to store cumulative weights
            cumulativeWeights[0] =
                entities.getFirst().getWeight(); // Initialize the first element with the first source's weight
            for (int i = 1; i < entities.size(); i++) {
                cumulativeWeights[i] =
                    cumulativeWeights[i - 1] + entities.get(i).getWeight(); // Calculate cumulative weights
            }

            int totalWeight = cumulativeWeights[cumulativeWeights.length - 1]; // Total weight of all entities
            int randomIndex = random.nextInt(totalWeight); // Generate a random number within the total weight

            int selectedIndex = IntStream.range(0, cumulativeWeights.length)
                .filter(i -> cumulativeWeights[i] > randomIndex)
                .findFirst()
                .orElse(
                    cumulativeWeights.length - 1); // Find the index of the selected source based on the random number

            selectedEntities.add(entities.get(selectedIndex)); // Add the selected source to the list
            entities.remove(selectedIndex); // Remove the selected source from the original list
        }

        return selectedEntities; // Return the list of selected entities
    }
}
