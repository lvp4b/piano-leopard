package net.kreatious.pianoleopard.midi.sequencer;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.ShortMessage;

import net.kreatious.pianoleopard.midi.ParsedSequence;
import net.kreatious.pianoleopard.midi.ParsedTrack;
import net.kreatious.pianoleopard.midi.event.EventPair;
import net.kreatious.pianoleopard.midi.event.NoteEvent;
import net.kreatious.pianoleopard.midi.sequencer.Keys.KeyIterator;

/**
 * Provides support for lighted keyboards.
 * <p>
 * This sends MIDI messages to turn on keyboard lights slightly ahead of the
 * actual song, allowing the user time to react.
 *
 * @author Jay-R Studer
 */
public class LightedKeyboardController {
    private static final long OFFSET = TimeUnit.MILLISECONDS.toMicros(500);
    private static final long GAP_BETWEEN_NOTES = TimeUnit.MILLISECONDS.toMicros(100);
    private static final int NAVIGATION_CHANNEL = 15;

    private final OutputModel outputModel;
    private final Keys litKeys = new Keys();
    private final Keys keysToLight = new Keys();

    private volatile ParsedSequence sequence = ParsedSequence.createEmpty();

    private LightedKeyboardController(OutputModel outputModel) {
        this.outputModel = outputModel;
    }

    /**
     * Constructs and starts a new instance of {@link LightedKeyboardController}
     * to control a lighted keyboard. After construction, keys in the current
     * sequence will light up slightly ahead of the actual song.
     *
     * @param outputModel
     *            the {@link OutputModel} to send control events to
     * @return a new instance of {@link LightedKeyboardController}
     */
    public static LightedKeyboardController create(OutputModel outputModel) {
        final LightedKeyboardController result = new LightedKeyboardController(outputModel);
        result.outputModel.addCurrentTimeListener(result::setCurrentTime);
        result.outputModel.addOpenListener(result::setCurrentSequence);
        return result;
    }

    private void setCurrentSequence(ParsedSequence sequence) {
        this.sequence = sequence;
    }

    private void setCurrentTime(long time) {
        try {
            updateKeysToLight(time);
            updateLitKeys();
        } catch (final InvalidMidiDataException e) {
            // Unreachable
            throw new IllegalStateException(e);
        }
    }

    private void updateKeysToLight(long time) {
        final long offsetTime = time + OFFSET;
        for (final ParsedTrack track : sequence.getTracks()) {
            final Iterator<EventPair<NoteEvent>> it = track.getNotePairs(offsetTime, offsetTime).iterator();
            while (it.hasNext()) {
                final EventPair<NoteEvent> note = it.next();

                // Filter out lit keys based on certain criteria
                if (note.getDuration() <= TimeUnit.MILLISECONDS.toMicros(10)) {
                    continue;
                } else if (offsetTime >= note.getOffTime() - GAP_BETWEEN_NOTES) {
                    continue;
                }

                keysToLight.add(note.getOff().getKey());
            }
        }
    }

    private void updateLitKeys() throws InvalidMidiDataException {
        final KeyIterator litKeysIt = litKeys.iterator();
        while (litKeysIt.hasNext()) {
            final int key = litKeysIt.next();
            if (!keysToLight.contains(key)) {
                outputModel.sendMessage(new ShortMessage(ShortMessage.NOTE_OFF, NAVIGATION_CHANNEL, key, 127));
                litKeysIt.remove();
            }
        }

        final KeyIterator keysToLightIt = keysToLight.iterator();
        while (keysToLightIt.hasNext()) {
            final int key = keysToLightIt.next();
            if (!litKeys.contains(key)) {
                outputModel.sendMessage(new ShortMessage(ShortMessage.NOTE_ON, NAVIGATION_CHANNEL, key, 1));
                litKeys.add(key);
            }
        }
        keysToLight.clear();
    }
}