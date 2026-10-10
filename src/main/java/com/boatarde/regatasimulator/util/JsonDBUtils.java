package com.boatarde.regatasimulator.util;

import com.boatarde.regatasimulator.models.AreaCorner;
import com.boatarde.regatasimulator.models.Author;
import com.boatarde.regatasimulator.models.CommonEntity;
import com.boatarde.regatasimulator.models.Meme;
import com.boatarde.regatasimulator.models.Source;
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
import java.util.random.RandomGenerator;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.IntStream;

@Slf4j
@UtilityClass
public class JsonDBUtils {
    private static final String HEADER = "Area,Source,TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,Background";

    public static JxQueryBuilder jxQuery() {
        return new JxQueryBuilder();
    }

    public static <T extends CommonEntity> void excludeRecent(List<T> candidates, Stream<UUID> recent, int required) {
        int budget = Math.min((int) Math.ceil(candidates.size() * 0.75), candidates.size() - required);
        if (budget <= 0) {
            return;
        }
        List<UUID> excluded = recent.distinct()
            .filter(id -> candidates.stream().anyMatch(item -> item.getId().equals(id)))
            .limit(budget).toList();
        candidates.removeIf(item -> excluded.contains(item.getId()));
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
        return selectSourcesWithWeight(sources, amount, new Random());
    }

    public static List<Source> selectSourcesWithWeight(List<Source> sources, int amount, RandomGenerator random) {
        return selectWithWeight(sources, amount, random);
    }

    public static List<Template> selectTemplatesWithWeight(List<Template> templates, int amount) {
        return selectTemplatesWithWeight(templates, amount, new Random());
    }

    public static List<Template> selectTemplatesWithWeight(List<Template> templates, int amount,
                                                          RandomGenerator random) {
        return selectWithWeight(templates, amount, random);
    }

    public static Template selectRandomSingleAreaTemplate(List<Template> templates) {
        return selectRandomSingleAreaTemplate(templates, new Random());
    }

    public static Template selectRandomSingleAreaTemplate(List<Template> templates, RandomGenerator random) {
        List<Template> filteredTemplates = templates.stream()
            .filter(template -> template.getAreas().size() == 1)
            .toList();

        if (filteredTemplates.isEmpty()) {
            throw new IllegalStateException("No single area templates found");
        }

        return filteredTemplates.get(random.nextInt(filteredTemplates.size()));
    }

    private static <T extends CommonEntity> List<T> selectWithWeight(List<T> entities, int amount,
                                                                  RandomGenerator random) {
        if (entities.size() < amount) {
            throw new IllegalArgumentException(
                "Not enough entities to select from. Amount requested: %d, entities available: %d".formatted(amount,
                    entities.size()));
        }
        List<T> selectedEntities = new ArrayList<>(); // List to store selected entities

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

            // Move the candidate at this position out of the pool to select without replacement.
            selectedEntities.add(entities.remove(selectedIndex));
        }

        return selectedEntities; // Return the list of selected entities
    }

    public static List<TemplateArea> parseTemplateCsv(String csv) throws IOException {
        if (csv == null || csv.length() > 64 * 1024) {
            throw new IOException("CSV content exceeds limits or is absent");
        }
        List<TemplateArea> areas = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
            String line = reader.readLine(); // header
            if (!HEADER.equals(line)) {
                throw new IOException("Invalid CSV header");
            }
            while ((line = reader.readLine()) != null) {
                if (areas.size() >= MediaValidation.MAX_AREAS) {
                    throw new IOException("Too many template areas");
                }
                String[] fields = line.split(",", -1);
                if (fields.length != 11 || java.util.Arrays.stream(fields).anyMatch(String::isBlank)) {
                    throw new IOException("Invalid CSV format");
                }
                int background = Integer.parseInt(fields[10]);
                if (background != 0 && background != 1) {
                    throw new IOException("Background must be 0 or 1");
                }

                areas.add(TemplateArea.builder()
                    .index(Integer.parseInt(fields[0]))
                    .source(Integer.parseInt(fields[1]))
                    .topLeft(AreaCorner.builder()
                        .x(Integer.parseInt(fields[2]))
                        .y(Integer.parseInt(fields[3]))
                        .build())
                    .topRight(AreaCorner.builder()
                        .x(Integer.parseInt(fields[4]))
                        .y(Integer.parseInt(fields[5]))
                        .build())
                    .bottomRight(AreaCorner.builder()
                        .x(Integer.parseInt(fields[6]))
                        .y(Integer.parseInt(fields[7]))
                        .build())
                    .bottomLeft(AreaCorner.builder()
                        .x(Integer.parseInt(fields[8]))
                        .y(Integer.parseInt(fields[9]))
                        .build())
                    .background(background == 1)
                    .build());
            }
        } catch (NumberFormatException e) {
            throw new IOException("Invalid CSV number", e);
        }
        MediaValidation.geometry(areas, null);
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
