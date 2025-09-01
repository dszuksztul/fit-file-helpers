package org.example;

import com.garmin.fit.*;
import com.google.common.io.Files;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class FitBoundsExtractor {

    private static final String POSITION_LAT_FIELD = "position_lat";
    private static final String POSITION_LONG_FIELD = "position_long";

    public static double semicirclesToDegrees(long value) {
        return value * (180.0 / Math.pow(2, 31));
    }

    public static long degreesToSemicircles(double value) {
        return (long) (value * (Math.pow(2, 31) / 180.0));
    }

    public static Mesg translate(Mesg mesg) {
        return switch (mesg.getNum()) {
            case MesgNum.RECORD -> new RecordMesg(mesg);
            case MesgNum.SESSION -> new SessionMesg(mesg);
            default -> mesg;
        };
    }

    public static List<Mesg> extractBounds(String fitFilePath) throws IOException {
        List<Mesg> allMessages = loadAndDecodeRelevantMessages(fitFilePath);
        allMessages = filterPositionOutliers(allMessages);
        allMessages = filterSpeedOutliers(allMessages);

        return allMessages;
    }

    final static int SPEED_LIMIT = 30; //m/s - still that's a lot

    private static List<Mesg> filterSpeedOutliers(List<Mesg> allMessages) {
        List<RecordMesg> recordMesgs =
                allMessages.stream()
                        .filter(RecordMesg.class::isInstance)
                        .map(RecordMesg.class::cast)
                        .filter(msg -> msg.getPositionLat() != null && msg.getPositionLong() != null)
                        .sorted(Comparator.comparing(RecordMesg::getTimestamp))
                        .toList();
        Long prevTimetamp = null;
        Integer prevLong = null;
        Integer prevLat = null;

        Set<Mesg> suspicious = new HashSet<>();
        int badRun = 0;
        for (RecordMesg mesg : recordMesgs) {
            if (prevTimetamp == null) {
                prevTimetamp = mesg.getTimestamp().getTimestamp();
                prevLong = mesg.getPositionLong();
                prevLat = mesg.getPositionLat();
                continue;
            }

            var timeDiff = mesg.getTimestamp().getTimestamp() - prevTimetamp;
            if (timeDiff == 0) {
                System.out.println("infinite...");
                continue;
            }
            var distanceDiff = distanceBetweenPoints(
                    prevLat, prevLong,
                    mesg.getPositionLat(), mesg.getPositionLong());

            double speed = distanceDiff / timeDiff;
            if (speed > SPEED_LIMIT) {
                suspicious.add(mesg);
                badRun ++;
                if (badRun>10) {
                    throw new RuntimeException("It looks like speed is not recovering...");
                }
            } else {
                badRun = 0;
                prevTimetamp = mesg.getTimestamp().getTimestamp();
                prevLat = mesg.getPositionLat();
                prevLong = mesg.getPositionLong();
            }
        }

        return allMessages
                .stream()
                .filter(msg -> !suspicious.contains(msg))
                .toList();
    }

    static double haversine(double val) {
        return Math.pow(Math.sin(val / 2), 2);
    }

    public static double distanceBetweenPoints(long lat1_s, long lon1_s, long lat2_s, long lon2_s) {
        double startLat = Math.toRadians(semicirclesToDegrees(lat1_s));
        double endLat = Math.toRadians(semicirclesToDegrees(lat2_s));
        double lon1 = Math.toRadians(semicirclesToDegrees(lon1_s));
        double lon2 = Math.toRadians(semicirclesToDegrees(lon2_s));

        double dLat = endLat - startLat;
        double dLon = lon2 - lon1;

        double R = 6_371_000; // meters

        double a = haversine(dLat) + Math.cos(startLat) * Math.cos(endLat) * haversine(dLon);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return R * c;
    }

    private static List<Mesg> filterPositionOutliers(List<Mesg> allMessages) {
        List<RecordMesg> recordMesgs =
                allMessages.stream()
                        .filter(RecordMesg.class::isInstance)
                        .map(RecordMesg.class::cast)
                        .toList();

        double totalDistanceM = allMessages
                .stream()
                .filter(SessionMesg.class::isInstance)
                .map(SessionMesg.class::cast)
                .map(SessionMesg::getTotalDistance)
                .filter(Objects::nonNull)
                .findFirst()
                .get();

        double earthCircumferenceM = 40_075_000;
        long totalSemicircle = degreesToSemicircles(360 * totalDistanceM / earthCircumferenceM);

        List<Integer> latitudes = new ArrayList<>();
        List<Integer> longitudes = new ArrayList<>();

        for (RecordMesg record : recordMesgs) {
            Integer lat = record.getPositionLat();
            Integer lon = record.getPositionLong();

            if (lat != null && lon != null) {
                latitudes.add(lat);
                longitudes.add(lon);
            }
        }

        Set<Integer> longOutliers = calcOutliers(longitudes, totalSemicircle);
        Set<Integer> latOutliers = calcOutliers(latitudes, totalSemicircle);

        Predicate<Mesg> notARecord = x -> x.getNum() != MesgNum.RECORD;
        Predicate<Mesg> notPositionOutlier = x -> !isOutlier((RecordMesg) x, latOutliers, longOutliers);

        allMessages = allMessages
                .stream()
                .filter(notARecord.or(notPositionOutlier))
                .toList();
        return allMessages;
    }

    private static List<Mesg> loadAndDecodeRelevantMessages(String fitFilePath) throws FileNotFoundException {
        FileInputStream inputStream = new FileInputStream(fitFilePath);
        System.out.println("Decoding file...");
        Decode decode = new Decode();
        List<Mesg> allMessages = new ArrayList<>();

        decode.read(inputStream, allMessages::add);

        // deocode session and record mesgs
        allMessages = allMessages
                .stream()
                .map(FitBoundsExtractor::translate)
                .toList();
        return allMessages;
    }

    static boolean isOutlier(RecordMesg record, Set<Integer> latOutliers, Set<Integer> longOutliers) {
        Integer lat = record.getPositionLat();
        Integer lon = record.getPositionLong();

        return (lat != null && lon != null) && (latOutliers.contains(lat) || longOutliers.contains(lon));
    }

    private static Set<Integer> calcOutliers(List<Integer> semicircles, long totalSemicircle) {
        if (semicircles.isEmpty()) return Collections.emptySet();

        // Group by segment index
        Map<Integer, Long> counts = new HashMap<>();
        for (Integer l : semicircles) {
            int segment = (int) (l / totalSemicircle);
            counts.put(segment, counts.getOrDefault(segment, 0L) + 1);
        }

        // Find mode
        int modeSegment = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0);

        long modeValue = totalSemicircle * modeSegment;

        // Mark outliers
        return semicircles.stream()
                .filter(l -> Math.abs(l - modeValue) > totalSemicircle)
                .collect(Collectors.toSet());
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: java FitBoundsExtractor <file.fit>");
            System.exit(1);
        }

        String fitFile = args[0];
        var filteredMessages = extractBounds(fitFile);

        var sourcePath = new File(fitFile).toPath();
        var nameNoExtension = Files.getNameWithoutExtension(sourcePath.getFileName().toString());
        var extension = Files.getFileExtension(sourcePath.getFileName().toString());

        var targetFile = sourcePath.getParent().resolve(nameNoExtension + "-new." + extension).toFile();

        var encode = new FileEncoder(targetFile, Fit.ProtocolVersion.V2_0);
        try {
            encode.write(filteredMessages);
        } finally {
            encode.close();
        }

        System.out.println("Created file: " + targetFile);
    }
}
