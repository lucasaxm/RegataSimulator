package com.boatarde.regatasimulator.util;

import com.boatarde.regatasimulator.models.AreaCorner;
import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.models.Meme;
import com.boatarde.regatasimulator.models.Source;
import com.boatarde.regatasimulator.models.Status;
import com.boatarde.regatasimulator.models.Template;
import com.boatarde.regatasimulator.models.TemplateArea;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.Message;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
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
    private static final String HEADER = "Area,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background";

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

    public static Template selectRandomSingleAreaTemplate(List<Template> templates) {
        List<Template> filteredTemplates = templates.stream()
            .filter(template -> template.getAreas().size() == 1)
            .toList();

        if (filteredTemplates.isEmpty()) {
            throw new RuntimeException("No single area templates found");
        }

        Random random = new Random();
        return filteredTemplates.get(random.nextInt(filteredTemplates.size()));
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

    public static List<TemplateArea> parseTemplateCsv(String csv) throws IOException {
        List<TemplateArea> areas = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
            String line = reader.readLine(); // header
            if (!HEADER.equals(line)) {
                throw new IOException("Invalid CSV header");
            }
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(",");
                if (fields.length != 10) {
                    throw new IOException("Invalid CSV format");
                }

                areas.add(TemplateArea.builder()
                    .index(Integer.parseInt(fields[0]))
                    .topLeft(AreaCorner.builder()
                        .x(Integer.parseInt(fields[1]))
                        .y(Integer.parseInt(fields[2]))
                        .build())
                    .topRight(AreaCorner.builder()
                        .x(Integer.parseInt(fields[3]))
                        .y(Integer.parseInt(fields[4]))
                        .build())
                    .bottomRight(AreaCorner.builder()
                        .x(Integer.parseInt(fields[5]))
                        .y(Integer.parseInt(fields[6]))
                        .build())
                    .bottomLeft(AreaCorner.builder()
                        .x(Integer.parseInt(fields[7]))
                        .y(Integer.parseInt(fields[8]))
                        .build())
                    .background(Integer.parseInt(fields[9]) == 1)
                    .build());
            }
        }
        return areas;
    }

    public static String usernameOrFullName(Author author) {
        if (author.getUserName() != null && !author.getUserName().isEmpty()) {
            return "@" + author.getUserName();
        }
        String fullName = author.getFirstName();
        if (author.getLastName() != null && !author.getLastName().isEmpty()) {
            fullName += " " + author.getLastName();
        }
        return "<a href=\"tg://user?id=%d\">%s</a>".formatted(author.getId(), fullName);
    }
}
