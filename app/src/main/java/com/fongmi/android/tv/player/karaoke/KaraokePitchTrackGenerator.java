package com.fongmi.android.tv.player.karaoke;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.lyrics.LyricsLine;
import com.fongmi.android.tv.player.lyrics.LyricsWord;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class KaraokePitchTrackGenerator {

    private static final double BPM = 6000.0;
    private static final long BEAT_MS = 10;
    private static final int RAP_PITCH = 60;
    private static final int MAX_NOTES = 900;
    private static final long MIN_NOTE_MS = 120;
    private static final long MIN_STABLE_NOTE_MS = 180;
    private static final long DEFAULT_LAST_LINE_MS = 3000;
    private static final long LINE_UNIT_MS = 520;
    private static final int MAX_LINE_UNITS = 10;
    private static final int FRAME_SIZE = 2048;
    private static final int HOP_SIZE = 1024;
    private static final double MIN_FREQUENCY_HZ = 80.0;
    private static final double MAX_FREQUENCY_HZ = 800.0;
    private static final double MIN_VOLUME = 0.006;
    private static final double MIN_CONFIDENCE = 0.18;
    private static final double MIN_WINDOW_VALID_RATIO = 0.14;
    private static final double MAX_WINDOW_SPREAD = 8.0;
    private static final double MIN_RAW_PITCH_RATIO = 0.08;
    private static final double MIN_TRACK_PITCH_RATIO = 0.18;
    private static final long WINDOW_MARGIN_MS = 70;
    private static final int MIN_MIDI = 40;
    private static final int MAX_MIDI = 84;
    private static final int GAP_FILL_MAX_NOTES = 8;
    private static final int OUTLIER_STEP = 9;
    private static final int MERGE_STRONG_DELTA = 1;
    private static final int MERGE_WEAK_DELTA = 2;
    private static final long MERGE_GAP_MS = 140;
    private static final long MERGE_MAX_MS = 2600;
    private static final double KEY_CORRECTION_MAX_QUALITY = 0.68;
    private static final double[][] KEY_TABLE = {
            {0.19, 0.00, 0.21, 0.00, 0.21, 0.08, 0.00, 0.13, 0.00, 0.11, 0.00, 0.07},
            {0.09, 0.21, 0.00, 0.17, 0.00, 0.18, 0.08, 0.00, 0.13, 0.00, 0.14, 0.00},
            {0.00, 0.07, 0.19, 0.00, 0.18, 0.00, 0.17, 0.08, 0.00, 0.19, 0.00, 0.12},
            {0.11, 0.00, 0.10, 0.26, 0.00, 0.16, 0.00, 0.17, 0.07, 0.00, 0.13, 0.00},
            {0.00, 0.14, 0.00, 0.07, 0.28, 0.00, 0.17, 0.00, 0.13, 0.06, 0.00, 0.15},
            {0.15, 0.00, 0.16, 0.00, 0.13, 0.17, 0.00, 0.16, 0.00, 0.13, 0.10, 0.00},
            {0.00, 0.15, 0.00, 0.16, 0.00, 0.12, 0.17, 0.00, 0.14, 0.00, 0.14, 0.12},
            {0.09, 0.00, 0.16, 0.00, 0.16, 0.00, 0.11, 0.17, 0.00, 0.16, 0.00, 0.15},
            {0.18, 0.07, 0.00, 0.15, 0.00, 0.14, 0.00, 0.09, 0.19, 0.00, 0.18, 0.00},
            {0.00, 0.18, 0.10, 0.00, 0.14, 0.00, 0.15, 0.00, 0.10, 0.17, 0.00, 0.16},
            {0.13, 0.00, 0.15, 0.09, 0.00, 0.16, 0.00, 0.18, 0.00, 0.12, 0.17, 0.00},
            {0.00, 0.11, 0.00, 0.19, 0.13, 0.00, 0.16, 0.00, 0.12, 0.00, 0.08, 0.21}
    };
    public static final int STAGE_PREPARE = 0;
    public static final int STAGE_DECODE = 1;
    public static final int STAGE_ANALYZE = 2;
    public static final int STAGE_WRITE = 3;
    public static final int STAGE_FINISH = 4;

    private KaraokePitchTrackGenerator() {
    }

    public interface Progress {

        void onProgress(int percent, int stage, long elapsedMs, long remainingMs);
    }

    public static boolean canGenerate(KaraokeTrackRepository.MediaInput input, List<LyricsLine> lines) {
        return input != null && !input.isEmpty() && KaraokeGeneratedTrackBuilder.canGenerate(lines);
    }

    public static String build(KaraokeTrackRepository.MediaInput input, List<LyricsLine> lines) throws Exception {
        return build(input, lines, null);
    }

    public static String build(KaraokeTrackRepository.MediaInput input, List<LyricsLine> lines, Progress progress) throws Exception {
        ProgressReporter reporter = new ProgressReporter(progress);
        if (!canGenerate(input, lines)) throw new IllegalStateException("no timed lyrics");
        reporter.update(1, STAGE_PREPARE);
        List<Segment> segments = segments(lines, input.getDuration());
        if (segments.size() < 3) throw new IllegalStateException("not enough lyric timing");
        List<PitchFrame> frames = decode(input, reporter);
        if (frames.isEmpty()) throw new IllegalStateException("no pitch frames");
        reporter.update(78, STAGE_ANALYZE);
        String text = buildText(input.getKeyword(), input.getArtist(), segments, frames, reporter);
        reporter.update(100, STAGE_FINISH);
        return text;
    }

    private static String buildText(String title, String artist, List<Segment> segments, List<PitchFrame> frames, ProgressReporter reporter) {
        StringBuilder builder = new StringBuilder();
        builder.append("#TITLE:").append(tag(title, "Generated pitch track")).append('\n');
        builder.append("#ARTIST:").append(tag(artist, "Unknown")).append('\n');
        builder.append("#BPM:").append(BPM).append('\n');
        builder.append("#GAP:0").append('\n');
        builder.append("#COMMENT:Generated experimental pitch scoring track from local audio; octave corrected and smoothed").append('\n');
        List<Note> notes = notes(segments, frames);
        reporter.update(82, STAGE_ANALYZE);
        stabilize(notes);
        smoothLineContour(notes);
        notes = mergeNotes(notes);
        applyPseudoKey(notes);
        correctOctaves(notes);
        smoothOutliers(notes);
        reporter.update(90, STAGE_WRITE);
        int count = 0;
        int observed = 0;
        int pitched = 0;
        for (Note note : notes) {
            if (count >= MAX_NOTES) break;
            char prefix = note.pitch >= 0 ? ':' : 'R';
            if (note.observed) observed++;
            if (note.pitch >= 0) pitched++;
            appendNote(builder, prefix, note.segment.startMs, note.segment.endMs, note.pitch >= 0 ? note.pitch : RAP_PITCH, note.segment.text);
            count++;
            if (note.segment.lineEnd) builder.append("-\n");
        }
        if (count < 3) throw new IllegalStateException("not enough notes");
        if (observed < 3 || observed < Math.round(count * MIN_RAW_PITCH_RATIO)) throw new IllegalStateException("pitch quality too low");
        if (pitched < 3 || pitched < Math.round(count * MIN_TRACK_PITCH_RATIO)) throw new IllegalStateException("pitch quality too low");
        builder.append('E').append('\n');
        return builder.toString();
    }

    private static List<Note> notes(List<Segment> segments, List<PitchFrame> frames) {
        List<Note> notes = new ArrayList<>();
        for (Segment segment : segments) {
            PitchCandidate candidate = PitchWindow.from(frames, segment.startMs, segment.endMs).candidate();
            notes.add(new Note(segment, candidate));
        }
        return notes;
    }

    private static void stabilize(List<Note> notes) {
        correctOctaves(notes);
        smoothOutliers(notes);
        fillMissing(notes);
        correctOctaves(notes);
        smoothOutliers(notes);
    }

    private static void smoothLineContour(List<Note> notes) {
        int start = 0;
        while (start < notes.size()) {
            int end = start;
            while (end < notes.size() && !notes.get(end).segment.lineEnd) end++;
            end = Math.min(notes.size() - 1, end);
            smoothLineContour(notes, start, end);
            start = end + 1;
        }
    }

    private static void smoothLineContour(List<Note> notes, int start, int end) {
        List<Integer> pitches = new ArrayList<>();
        for (int i = start; i <= end; i++) if (notes.get(i).pitch >= 0) pitches.add(notes.get(i).pitch);
        if (pitches.size() < 2) return;
        Collections.sort(pitches);
        int median = pitches.get(pitches.size() / 2);
        int low = pitches.get(0);
        int high = pitches.get(pitches.size() - 1);
        boolean mostlyFlat = high - low <= 2;
        for (int i = start; i <= end; i++) {
            Note note = notes.get(i);
            if (note.pitch < 0) continue;
            int normalized = normalizeOctave(note.pitch, median);
            if ((Math.abs(normalized - median) <= 1 || mostlyFlat) && (note.quality < 0.55 || note.estimated)) {
                note.pitch = median;
                note.estimated = true;
            } else {
                note.pitch = normalized;
            }
        }
    }

    private static List<Note> mergeNotes(List<Note> notes) {
        List<Note> merged = new ArrayList<>();
        for (Note note : notes) {
            if (!merged.isEmpty() && canMerge(merged.get(merged.size() - 1), note)) {
                merged.set(merged.size() - 1, merge(merged.get(merged.size() - 1), note));
            } else {
                merged.add(note);
            }
        }
        return merged;
    }

    private static boolean canMerge(Note previous, Note next) {
        if (previous == null || next == null || previous.segment.lineEnd) return false;
        if (previous.pitch < 0 || next.pitch < 0) return false;
        if (next.segment.startMs - previous.segment.endMs > MERGE_GAP_MS) return false;
        long duration = next.segment.endMs - previous.segment.startMs;
        if (duration > MERGE_MAX_MS) return false;
        int delta = Math.abs(normalizeOctave(next.pitch, previous.pitch) - previous.pitch);
        if (delta <= MERGE_STRONG_DELTA) return true;
        long previousDuration = previous.segment.endMs - previous.segment.startMs;
        long nextDuration = next.segment.endMs - next.segment.startMs;
        return delta <= MERGE_WEAK_DELTA
                && (previous.estimated || next.estimated || previous.quality < 0.55 || next.quality < 0.55 || Math.min(previousDuration, nextDuration) < MIN_STABLE_NOTE_MS);
    }

    private static Note merge(Note previous, Note next) {
        int nextPitch = normalizeOctave(next.pitch, previous.pitch);
        long previousDuration = Math.max(1, previous.segment.endMs - previous.segment.startMs);
        long nextDuration = Math.max(1, next.segment.endMs - next.segment.startMs);
        int pitch = clampMidi(Math.round((previous.pitch * previousDuration + nextPitch * nextDuration) / (float) (previousDuration + nextDuration)));
        Segment segment = new Segment(previous.segment.startMs, next.segment.endMs, previous.segment.text + next.segment.text);
        segment.lineEnd = next.segment.lineEnd;
        boolean estimated = previous.estimated || next.estimated || previous.pitch != pitch || nextPitch != pitch;
        boolean observed = previous.observed || next.observed;
        double quality = Math.max(previous.quality, next.quality);
        return new Note(segment, pitch, observed, quality, estimated);
    }

    private static void applyPseudoKey(List<Note> notes) {
        int key = detectPseudoKey(notes);
        if (key < 0) return;
        for (Note note : notes) {
            if (note.pitch < 0 || (!note.estimated && note.quality > KEY_CORRECTION_MAX_QUALITY)) continue;
            int pc = pitchClass(note.pitch);
            if (KEY_TABLE[key][pc] > 0) continue;
            int up = pitchClass(note.pitch + 1);
            int down = pitchClass(note.pitch - 1);
            int shift = KEY_TABLE[key][up] >= KEY_TABLE[key][down] ? 1 : -1;
            note.pitch = clampMidi(note.pitch + shift);
            note.estimated = true;
        }
    }

    private static int detectPseudoKey(List<Note> notes) {
        double[] distribution = new double[12];
        double total = 0;
        for (Note note : notes) {
            if (note.pitch < 0) continue;
            double duration = Math.max(1, note.segment.endMs - note.segment.startMs);
            double weight = duration * Math.max(0.25, note.quality);
            distribution[pitchClass(note.pitch)] += weight;
            total += weight;
        }
        if (total <= 0) return -1;
        int best = -1;
        double bestScore = 0;
        for (int key = 0; key < KEY_TABLE.length; key++) {
            double score = 0;
            for (int pc = 0; pc < distribution.length; pc++) score += KEY_TABLE[key][pc] * distribution[pc];
            if (score > bestScore) {
                bestScore = score;
                best = key;
            }
        }
        return best;
    }

    private static void correctOctaves(List<Note> notes) {
        Integer anchor = null;
        for (Note note : notes) {
            if (note.pitch < 0) continue;
            if (anchor != null) note.pitch = normalizeOctave(note.pitch, anchor);
            if (anchor != null && Math.abs(note.pitch - anchor) > OUTLIER_STEP && note.quality < 0.45) {
                note.pitch = -1;
                note.estimated = true;
                continue;
            }
            anchor = note.pitch;
        }
        anchor = null;
        for (int i = notes.size() - 1; i >= 0; i--) {
            Note note = notes.get(i);
            if (note.pitch < 0) continue;
            if (anchor != null) note.pitch = normalizeOctave(note.pitch, anchor);
            anchor = note.pitch;
        }
    }

    private static void smoothOutliers(List<Note> notes) {
        for (int i = 0; i < notes.size(); i++) {
            Note note = notes.get(i);
            if (note.pitch < 0) continue;
            Neighbor previous = previous(notes, i);
            Neighbor next = next(notes, i);
            if (previous == null || next == null || previous.distance > 3 || next.distance > 3) continue;
            int target = Math.round((previous.note.pitch + next.note.pitch) / 2.0f);
            note.pitch = normalizeOctave(note.pitch, target);
            if (Math.abs(note.pitch - target) > OUTLIER_STEP && note.quality < 0.65) {
                note.pitch = clampMidi(target);
                note.estimated = true;
            }
        }
    }

    private static void fillMissing(List<Note> notes) {
        int index = 0;
        while (index < notes.size()) {
            if (notes.get(index).pitch >= 0) {
                index++;
                continue;
            }
            int start = index;
            while (index < notes.size() && notes.get(index).pitch < 0) index++;
            int end = index;
            int length = end - start;
            Note previous = start > 0 ? notes.get(start - 1) : null;
            Note next = end < notes.size() ? notes.get(end) : null;
            if (length > GAP_FILL_MAX_NOTES) continue;
            if (previous != null && previous.pitch >= 0 && next != null && next.pitch >= 0) {
                int nextPitch = normalizeOctave(next.pitch, previous.pitch);
                for (int i = start; i < end; i++) {
                    float progress = (i - start + 1) / (float) (length + 1);
                    notes.get(i).pitch = clampMidi(Math.round(previous.pitch + (nextPitch - previous.pitch) * progress));
                    notes.get(i).estimated = true;
                }
            } else if (previous != null && previous.pitch >= 0) {
                for (int i = start; i < end; i++) {
                    notes.get(i).pitch = previous.pitch;
                    notes.get(i).estimated = true;
                }
            } else if (next != null && next.pitch >= 0) {
                for (int i = start; i < end; i++) {
                    notes.get(i).pitch = next.pitch;
                    notes.get(i).estimated = true;
                }
            }
        }
    }

    private static Neighbor previous(List<Note> notes, int index) {
        for (int i = index - 1; i >= 0; i--) if (notes.get(i).pitch >= 0) return new Neighbor(notes.get(i), index - i);
        return null;
    }

    private static Neighbor next(List<Note> notes, int index) {
        for (int i = index + 1; i < notes.size(); i++) if (notes.get(i).pitch >= 0) return new Neighbor(notes.get(i), i - index);
        return null;
    }

    private static int normalizeOctave(int pitch, int reference) {
        int best = pitch;
        int bestDistance = Math.abs(best - reference);
        for (int candidate = pitch - 24; candidate <= pitch + 24; candidate += 12) {
            if (candidate < MIN_MIDI || candidate > MAX_MIDI) continue;
            int distance = Math.abs(candidate - reference);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return clampMidi(best);
    }

    private static int clampMidi(int pitch) {
        return Math.max(MIN_MIDI, Math.min(MAX_MIDI, pitch));
    }

    private static int pitchClass(int pitch) {
        return ((pitch % 12) + 12) % 12;
    }

    private static void appendNote(StringBuilder builder, char prefix, long startMs, long endMs, int pitch, String lyric) {
        long safeStart = Math.max(0, startMs);
        long safeEnd = Math.max(safeStart + MIN_NOTE_MS, endMs);
        int startBeat = (int) Math.max(0, Math.round(safeStart / (double) BEAT_MS));
        int lengthBeat = (int) Math.max(1, Math.round((safeEnd - safeStart) / (double) BEAT_MS));
        builder.append(prefix).append(' ')
                .append(startBeat).append(' ')
                .append(lengthBeat).append(' ')
                .append(pitch).append(' ')
                .append(lyric(lyric)).append('\n');
    }

    private static List<PitchFrame> decode(KaraokeTrackRepository.MediaInput input, ProgressReporter reporter) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            reporter.update(5, STAGE_DECODE);
            setDataSource(extractor, input.getUrl(), input.getHeaders());
            int track = selectAudioTrack(extractor);
            if (track < 0) throw new IllegalStateException("no audio track");
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (TextUtils.isEmpty(mime)) throw new IllegalStateException("unknown audio mime");
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();
            return decodeLoop(extractor, decoder, format, reporter, durationMs(input, format));
        } finally {
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (Exception ignored) {
                }
                decoder.release();
            }
        }
    }

    private static List<PitchFrame> decodeLoop(MediaExtractor extractor, MediaCodec decoder, MediaFormat inputFormat, ProgressReporter reporter, long durationMs) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        PitchFrameCollector collector = new PitchFrameCollector(sampleRate(inputFormat));
        boolean inputDone = false;
        boolean outputDone = false;
        MediaFormat outputFormat = inputFormat;
        while (!outputDone) {
            if (!inputDone) {
                int inputIndex = decoder.dequeueInputBuffer(10_000);
                if (inputIndex >= 0) {
                    ByteBuffer input = decoder.getInputBuffer(inputIndex);
                    if (input == null) continue;
                    int size = extractor.readSampleData(input, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        long sampleTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputIndex, 0, size, sampleTimeUs, extractor.getSampleFlags());
                        reporter.decode(sampleTimeUs, durationMs);
                        extractor.advance();
                    }
                }
            }
            int outputIndex = decoder.dequeueOutputBuffer(info, 10_000);
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outputFormat = decoder.getOutputFormat();
                collector.setSampleRate(sampleRate(outputFormat));
            } else if (outputIndex >= 0) {
                ByteBuffer output = decoder.getOutputBuffer(outputIndex);
                if (output != null && info.size > 0) collect(collector, output, info, outputFormat);
                outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                decoder.releaseOutputBuffer(outputIndex, false);
            }
        }
        return collector.frames();
    }

    private static void collect(PitchFrameCollector collector, ByteBuffer buffer, MediaCodec.BufferInfo info, MediaFormat format) {
        int channels = Math.max(1, format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1);
        int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING) ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
        ByteBuffer data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        data.position(info.offset);
        data.limit(info.offset + info.size);
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) collectFloat(collector, data, channels);
        else collectPcm16(collector, data, channels);
    }

    private static void collectPcm16(PitchFrameCollector collector, ByteBuffer data, int channels) {
        int frameBytes = channels * 2;
        int samples = data.remaining() / frameBytes;
        for (int i = 0; i < samples; i++) {
            float mono = 0;
            for (int c = 0; c < channels; c++) mono += data.getShort() / 32768f;
            collector.add(mono / channels);
        }
    }

    private static void collectFloat(PitchFrameCollector collector, ByteBuffer data, int channels) {
        int frameBytes = channels * 4;
        int samples = data.remaining() / frameBytes;
        for (int i = 0; i < samples; i++) {
            float mono = 0;
            for (int c = 0; c < channels; c++) mono += data.getFloat();
            collector.add(mono / channels);
        }
    }

    private static void setDataSource(MediaExtractor extractor, String url, Map<String, String> headers) throws Exception {
        if (TextUtils.isEmpty(url)) throw new IllegalStateException("empty url");
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if ("content".equalsIgnoreCase(scheme) || "android.resource".equalsIgnoreCase(scheme)) {
            extractor.setDataSource(App.get(), uri, headers);
        } else if ("file".equalsIgnoreCase(scheme)) {
            extractor.setDataSource(Uri.decode(uri.getPath()));
        } else if (TextUtils.isEmpty(scheme)) {
            extractor.setDataSource(url);
        } else {
            extractor.setDataSource(url, headers);
        }
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (!TextUtils.isEmpty(mime) && mime.toLowerCase(Locale.ROOT).startsWith("audio/")) return i;
        }
        return -1;
    }

    private static int sampleRate(MediaFormat format) {
        return format != null && format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? Math.max(1, format.getInteger(MediaFormat.KEY_SAMPLE_RATE)) : 44_100;
    }

    private static long durationMs(KaraokeTrackRepository.MediaInput input, MediaFormat format) {
        if (input != null && input.getDuration() > 0) return input.getDuration();
        if (format != null && format.containsKey(MediaFormat.KEY_DURATION)) return Math.max(0, format.getLong(MediaFormat.KEY_DURATION) / 1000L);
        return 0;
    }

    private static List<Segment> segments(List<LyricsLine> lines, long durationMs) {
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < lines.size() && segments.size() < MAX_NOTES; i++) {
            LyricsLine line = lines.get(i);
            if (line == null || TextUtils.isEmpty(line.getText())) continue;
            long startMs = line.getTimeMs();
            long endMs = lineEnd(lines, i, durationMs);
            int before = segments.size();
            if (line.hasWords()) appendWordSegments(segments, line, startMs, endMs);
            else appendLineSegments(segments, line.getText(), startMs, endMs);
            if (segments.size() > before) segments.get(segments.size() - 1).lineEnd = true;
        }
        return segments;
    }

    private static void appendWordSegments(List<Segment> segments, LyricsLine line, long lineStartMs, long lineEndMs) {
        List<LyricsWord> words = line.getWords();
        for (int i = 0; i < words.size() && segments.size() < MAX_NOTES; i++) {
            LyricsWord word = words.get(i);
            if (word == null || TextUtils.isEmpty(word.getText())) continue;
            long startMs = lineStartMs + word.getStartOffsetMs();
            long endMs = word.getDurationMs() > 0 ? startMs + word.getDurationMs() : nextWordStart(lineStartMs, lineEndMs, words, i);
            segments.add(new Segment(startMs, endMs, word.getText()));
        }
    }

    private static void appendLineSegments(List<Segment> segments, String text, long startMs, long endMs) {
        List<String> units = splitUnits(text, Math.max(MIN_NOTE_MS, endMs - startMs));
        if (units.isEmpty()) return;
        long durationMs = Math.max(MIN_NOTE_MS * units.size(), endMs - startMs);
        long unitMs = Math.max(MIN_NOTE_MS, durationMs / units.size());
        for (int i = 0; i < units.size() && segments.size() < MAX_NOTES; i++) {
            long unitStart = startMs + unitMs * i;
            long unitEnd = i == units.size() - 1 ? startMs + durationMs : unitStart + unitMs;
            segments.add(new Segment(unitStart, unitEnd, units.get(i)));
        }
    }

    private static long lineEnd(List<LyricsLine> lines, int index, long durationMs) {
        long startMs = lines.get(index).getTimeMs();
        long nextMs = index + 1 < lines.size() ? lines.get(index + 1).getTimeMs() : 0;
        long fallback = startMs + DEFAULT_LAST_LINE_MS;
        if (durationMs > startMs + MIN_NOTE_MS) fallback = Math.min(durationMs, fallback);
        if (nextMs <= startMs) return fallback;
        return Math.max(startMs + MIN_NOTE_MS, nextMs);
    }

    private static long nextWordStart(long lineStartMs, long lineEndMs, List<LyricsWord> words, int index) {
        if (index + 1 < words.size()) return lineStartMs + words.get(index + 1).getStartOffsetMs();
        return lineEndMs;
    }

    private static List<String> splitUnits(String text, long durationMs) {
        String clean = lyric(text);
        List<String> units = new ArrayList<>();
        if (TextUtils.isEmpty(clean)) return units;
        if (clean.matches(".*\\s+.*")) {
            for (String unit : clean.split("\\s+")) if (!TextUtils.isEmpty(unit)) units.add(unit);
            return compactUnits(units, targetUnitCount(units.size(), durationMs), " ");
        }
        List<String> chars = new ArrayList<>();
        for (int i = 0; i < clean.length(); ) {
            int codePoint = clean.codePointAt(i);
            chars.add(new String(Character.toChars(codePoint)));
            i += Character.charCount(codePoint);
        }
        return compactUnits(chars, targetUnitCount(chars.size(), durationMs), "");
    }

    private static int targetUnitCount(int sourceCount, long durationMs) {
        if (sourceCount <= 0) return 0;
        if (sourceCount <= 4) return sourceCount;
        int byDuration = Math.max(1, Math.round(durationMs / (float) LINE_UNIT_MS));
        int byText = Math.max(1, (int) Math.ceil(sourceCount / 2.0));
        return Math.max(1, Math.min(Math.min(MAX_LINE_UNITS, sourceCount), Math.min(byDuration, byText)));
    }

    private static List<String> compactUnits(List<String> source, int targetCount, String separator) {
        List<String> units = new ArrayList<>();
        if (source.isEmpty()) return units;
        int count = Math.max(1, Math.min(source.size(), targetCount));
        int groupSize = (int) Math.ceil(source.size() / (double) count);
        for (int i = 0; i < source.size(); i += groupSize) {
            StringBuilder builder = new StringBuilder();
            for (int j = i; j < Math.min(source.size(), i + groupSize); j++) {
                if (builder.length() > 0) builder.append(separator);
                builder.append(source.get(j));
            }
            units.add(builder.toString());
        }
        return units;
    }

    private static String tag(String value, String fallback) {
        String text = lyric(value);
        return TextUtils.isEmpty(text) ? fallback : text;
    }

    private static String lyric(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static class Segment {

        private final long startMs;
        private final long endMs;
        private final String text;
        private boolean lineEnd;

        private Segment(long startMs, long endMs, String text) {
            this.startMs = Math.max(0, startMs);
            this.endMs = Math.max(this.startMs + MIN_NOTE_MS, endMs);
            this.text = text;
        }
    }

    private static class Note {

        private final Segment segment;
        private final boolean observed;
        private final double quality;
        private int pitch;
        private boolean estimated;

        private Note(Segment segment, PitchCandidate candidate) {
            this(segment, candidate.pitch, candidate.pitch >= 0, candidate.quality, false);
        }

        private Note(Segment segment, int pitch, boolean observed, double quality, boolean estimated) {
            this.segment = segment;
            this.pitch = pitch >= 0 ? clampMidi(pitch) : -1;
            this.quality = Math.max(0, Math.min(1, quality));
            this.observed = observed;
            this.estimated = estimated;
        }
    }

    private static class PitchCandidate {

        private static final PitchCandidate EMPTY = new PitchCandidate(-1, 0);

        private final int pitch;
        private final double quality;

        private PitchCandidate(int pitch, double quality) {
            this.pitch = pitch >= 0 ? clampMidi(pitch) : -1;
            this.quality = Math.max(0, Math.min(1, quality));
        }
    }

    private static class Neighbor {

        private final Note note;
        private final int distance;

        private Neighbor(Note note, int distance) {
            this.note = note;
            this.distance = distance;
        }
    }

    private static class PitchFrame {

        private final long timeMs;
        private final double frequencyHz;
        private final double volume;
        private final double confidence;

        private PitchFrame(KaraokePitchSample sample) {
            this.timeMs = sample.getTimestampMs();
            this.frequencyHz = sample.getFrequencyHz();
            this.volume = sample.getVolume();
            this.confidence = sample.getConfidence();
        }

        private boolean valid() {
            return frequencyHz >= MIN_FREQUENCY_HZ && frequencyHz <= MAX_FREQUENCY_HZ && volume >= MIN_VOLUME && confidence >= MIN_CONFIDENCE;
        }

        private double midi() {
            return KaraokePitch.frequencyToMidi(frequencyHz);
        }

        private double weight() {
            return confidence * Math.sqrt(Math.max(MIN_VOLUME, volume));
        }
    }

    private static class PitchFrameCollector {

        private final float[] ring = new float[FRAME_SIZE];
        private final float[] frame = new float[FRAME_SIZE];
        private final List<PitchFrame> frames = new ArrayList<>();
        private YinPitchDetector detector;
        private long sampleCount;
        private int sampleRate;

        private PitchFrameCollector(int sampleRate) {
            setSampleRate(sampleRate);
        }

        private void setSampleRate(int sampleRate) {
            int safe = Math.max(1, sampleRate);
            if (this.sampleRate == safe && detector != null) return;
            this.sampleRate = safe;
            this.detector = new YinPitchDetector(safe, 0.12, 0.08, FRAME_SIZE);
        }

        private void add(float value) {
            ring[(int) (sampleCount % FRAME_SIZE)] = value;
            sampleCount++;
            if (sampleCount < FRAME_SIZE) return;
            if ((sampleCount - FRAME_SIZE) % HOP_SIZE != 0) return;
            copyFrame();
            long centerSample = sampleCount - FRAME_SIZE / 2L;
            long timeMs = Math.max(0, Math.round(centerSample * 1000.0 / sampleRate));
            frames.add(new PitchFrame(detector.detect(frame, frame.length, timeMs)));
        }

        private void copyFrame() {
            long start = sampleCount - FRAME_SIZE;
            for (int i = 0; i < FRAME_SIZE; i++) frame[i] = ring[(int) ((start + i) % FRAME_SIZE)];
        }

        private List<PitchFrame> frames() {
            return frames;
        }
    }

    private static class ProgressReporter {

        private static final long THROTTLE_MS = 350;

        private final Progress progress;
        private final long startMs = System.currentTimeMillis();
        private int lastPercent = -1;
        private int lastStage = -1;
        private long lastEmitMs;

        private ProgressReporter(Progress progress) {
            this.progress = progress;
        }

        private void decode(long sampleTimeUs, long durationMs) {
            if (durationMs <= 0 || sampleTimeUs < 0) {
                update(Math.max(lastPercent, 12), STAGE_DECODE);
                return;
            }
            double ratio = Math.max(0, Math.min(1, sampleTimeUs / (durationMs * 1000.0)));
            update(5 + (int) Math.round(ratio * 68), STAGE_DECODE);
        }

        private void update(int percent, int stage) {
            if (progress == null) return;
            long now = System.currentTimeMillis();
            int safePercent = Math.max(0, Math.min(100, Math.max(percent, lastPercent)));
            if (safePercent < 100 && safePercent == lastPercent && stage == lastStage && now - lastEmitMs < THROTTLE_MS) return;
            if (safePercent < 100 && now - lastEmitMs < THROTTLE_MS && stage == lastStage) return;
            lastPercent = safePercent;
            lastStage = stage;
            lastEmitMs = now;
            long elapsedMs = Math.max(0, now - startMs);
            long remainingMs = safePercent > 3 && safePercent < 100 ? Math.round(elapsedMs * (100 - safePercent) / (double) safePercent) : -1;
            progress.onProgress(safePercent, stage, elapsedMs, remainingMs);
        }
    }

    private static class PitchWindow {

        private final List<FrameValue> values;
        private final int total;

        private PitchWindow(List<FrameValue> values, int total) {
            this.values = values;
            this.total = total;
        }

        private static PitchWindow from(List<PitchFrame> frames, long startMs, long endMs) {
            List<FrameValue> values = new ArrayList<>();
            int total = 0;
            long safeStart = Math.max(0, startMs - WINDOW_MARGIN_MS);
            long safeEnd = Math.max(safeStart + MIN_NOTE_MS, endMs + WINDOW_MARGIN_MS);
            for (PitchFrame frame : frames) {
                if (frame.timeMs < safeStart) continue;
                if (frame.timeMs >= safeEnd) break;
                total++;
                if (frame.valid()) values.add(new FrameValue(frame.midi(), frame.weight()));
            }
            return new PitchWindow(values, total);
        }

        private PitchCandidate candidate() {
            if (values.size() < 2 || total <= 0) return PitchCandidate.EMPTY;
            double ratio = values.size() / (double) total;
            if (ratio < MIN_WINDOW_VALID_RATIO) return PitchCandidate.EMPTY;
            Collections.sort(values, (a, b) -> Double.compare(a.midi, b.midi));
            double median = values.get(values.size() / 2).midi;
            double spread = percentile(0.80) - percentile(0.20);
            if (spread > MAX_WINDOW_SPREAD) return PitchCandidate.EMPTY;
            int pitch = likelyPitch((int) Math.round(median));
            double modeQuality = modeQuality(pitch);
            double quality = ratio
                    * Math.max(0.15, 1.0 - spread / Math.max(1.0, MAX_WINDOW_SPREAD))
                    * Math.max(0.35, modeQuality);
            return new PitchCandidate(pitch, quality);
        }

        private double percentile(double p) {
            if (values.isEmpty()) return 0;
            int index = (int) Math.max(0, Math.min(values.size() - 1, Math.round((values.size() - 1) * p)));
            return values.get(index).midi;
        }

        private int likelyPitch(int median) {
            double[] scores = weightedScores();
            int best = -1;
            double bestScore = 0;
            for (int pitch = MIN_MIDI; pitch <= MAX_MIDI; pitch++) {
                if (scores[pitch] > bestScore) {
                    bestScore = scores[pitch];
                    best = pitch;
                }
            }
            if (best < 0) return clampMidi(median);
            return normalizeOctave(best, median);
        }

        private double modeQuality(int pitch) {
            double[] scores = weightedScores();
            double total = 0;
            for (FrameValue value : values) total += value.weight;
            if (total <= 0) return 0;
            double score = 0;
            for (int candidate = pitch - 24; candidate <= pitch + 24; candidate += 12) {
                if (candidate >= 0 && candidate < scores.length) score = Math.max(score, scores[candidate]);
            }
            return Math.max(0, Math.min(1, score / total));
        }

        private double[] weightedScores() {
            double[] scores = new double[MAX_MIDI + 1];
            for (FrameValue value : values) {
                int pitch = clampMidi((int) Math.round(value.midi));
                scores[pitch] += value.weight;
                if (pitch > MIN_MIDI) scores[pitch - 1] += value.weight * 0.22;
                if (pitch < MAX_MIDI) scores[pitch + 1] += value.weight * 0.22;
            }
            return scores;
        }
    }

    private static class FrameValue {

        private final double midi;
        private final double weight;

        private FrameValue(double midi, double weight) {
            this.midi = midi;
            this.weight = Math.max(0.01, weight);
        }
    }
}
