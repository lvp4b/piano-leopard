package net.kreatious.pianoleopard.midi.sequencer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;

import net.kreatious.pianoleopard.intervalset.IntervalSet;
import net.kreatious.pianoleopard.midi.ParsedSequence;
import net.kreatious.pianoleopard.midi.ParsedTrack;
import net.kreatious.pianoleopard.midi.event.Event;
import net.kreatious.pianoleopard.midi.event.EventFactory;
import net.kreatious.pianoleopard.midi.event.EventPair;
import net.kreatious.pianoleopard.midi.event.NoteEvent;
import net.kreatious.pianoleopard.midi.event.PedalEvent;

/**
 * Model for the MIDI input keyboard, allows controllers to listen for events.
 *
 * @author Jay-R Studer
 */
public class InputModel implements AutoCloseable, ParsedTrack {
    private Optional<MidiDevice> input = Optional.empty();
    private final ReceiverImpl receiver = new ReceiverImpl();

    private final IntervalSet<Long, EventPair<NoteEvent>> notes = new IntervalSet<>();
    private final IntervalSet<Long, EventPair<PedalEvent>> pedals = new IntervalSet<>();

    private InputModel(MidiDevice input) throws MidiUnavailableException {
        setInputDevice(input);
    }

    /**
     * Constructs a new {@link InputModel} with the specified initial state.
     * After construction, the input model is ready to listen for incoming
     * events from the user.
     *
     * @param input
     *            The initial input MIDI device to connect to
     * @param outputModel
     *            the output model to coordinate with
     * @return a new instance of {@link InputModel}. The caller is responsible
     *         for releasing the resource.
     * @throws MidiUnavailableException
     *             if the MIDI system is unavailable.
     */
    public static InputModel create(MidiDevice input, OutputModel outputModel) throws MidiUnavailableException {
        final InputModel result = new InputModel(input);
        outputModel.addStartListener(result::setCurrentSequence);
        outputModel.addCurrentTimeListener(result.receiver::setCurrentTime);
        return result;
    }

    private void setCurrentSequence(@SuppressWarnings("unused") ParsedSequence sequence) {
        notes.clear();
        pedals.clear();
        receiver.clear();
    }

    private final class ReceiverImpl implements Receiver, ParsedTrack {
        private final Map<Object, NoteEvent> onNotes = new HashMap<>();
        private final Map<Object, PedalEvent> onPedals = new HashMap<>();

        private long currentTime;

        private synchronized void setCurrentTime(long time) {
            currentTime = time;
        }

        @Override
        public synchronized void send(MidiMessage message, long timeStamp) {
            EventFactory.create(message, currentTime).ifPresent(this::userPressedEvent);
        }

        private void userPressedEvent(Event event) {
            if (event instanceof NoteEvent) {
                userPressedEvent((NoteEvent) event, onNotes, notes);
            } else if (event instanceof PedalEvent) {
                userPressedEvent((PedalEvent) event, onPedals, pedals);
            }
        }

        private <K extends Event> void userPressedEvent(K event, Map<Object, K> onEvents,
                IntervalSet<Long, EventPair<K>> fullEvents) {
            synchronized (fullEvents) {
                final long eventTime = event.getTime();
                if (event.isOn()) {
                    onEvents.put(event.getSlot(), event);
                } else {
                    Optional.ofNullable(onEvents.remove(event.getSlot())).ifPresent(onEvent -> {
                        fullEvents.put(onEvent.getTime(), eventTime, new EventPair<>(onEvent, event));
                    });
                }
            }
        }

        @Override
        public Iterable<EventPair<NoteEvent>> getNotePairs(long low, long high) {
            return getPairs(low, high, onNotes, notes);
        }

        @Override
        public Iterable<EventPair<PedalEvent>> getPedalPairs(long low, long high) {
            return getPairs(low, high, onPedals, pedals);
        }

        private <K extends Event> Iterable<EventPair<K>> getPairs(long low, long high, Map<Object, K> onEvents,
                IntervalSet<Long, EventPair<K>> fullEvents) {
            synchronized (fullEvents) {
                final List<EventPair<K>> result = new ArrayList<>();
                fullEvents.subSet(low, high).forEach(result::add);
                onEvents.values().forEach(event -> result.add(new EventPair<>(event, event.createOff(currentTime))));
                return result;
            }
        }

        void clear() {
            synchronized (notes) {
                onNotes.clear();
            }
            synchronized (pedals) {
                onPedals.clear();
            }
        }

        @Override
        public void close() {
            // Intentionally empty; this receiver holds no system resources
        }
    }

    /**
     * Reconnects the input to a different MIDI input device.
     *
     * @param input
     *            the new input MIDI device to reconnect to
     * @throws MidiUnavailableException
     *             if the MIDI system is unavailable.
     */
    public void setInputDevice(MidiDevice input) throws MidiUnavailableException {
        this.input.ifPresent(MidiDevice::close);
        this.input = Optional.of(input);

        input.open();
        input.getTransmitter().setReceiver(receiver);
    }

    @Override
    public void close() throws Exception {
        input.ifPresent(MidiDevice::close);
    }

    @Override
    public Iterable<EventPair<NoteEvent>> getNotePairs(long low, long high) {
        return receiver.getNotePairs(low, high);
    }

    @Override
    public Iterable<EventPair<PedalEvent>> getPedalPairs(long low, long high) {
        return receiver.getPedalPairs(low, high);
    }
}
