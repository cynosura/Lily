package org.lilycms.rowlog.impl.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.lilycms.rowlog.api.RowLogMessage;
import org.lilycms.rowlog.api.RowLogMessageListener;

public class ValidationMessageConsumer2 implements RowLogMessageListener {

    private static Map<RowLogMessage, Integer> expectedMessages = new HashMap<RowLogMessage, Integer>();
    private static Map<RowLogMessage, Integer> earlyMessages = new HashMap<RowLogMessage, Integer>();
    public static List<RowLogMessage> problematicMessages = new ArrayList<RowLogMessage>();
    private static int count = 0;
    private static int numberOfMessagesToBeExpected = 0;

    public static void reset() {
        expectedMessages = new HashMap<RowLogMessage, Integer>();
        earlyMessages = new HashMap<RowLogMessage, Integer>();
        problematicMessages = new ArrayList<RowLogMessage>();
        count = 0;
        numberOfMessagesToBeExpected = 0;
    }
    
    public static void expectMessage(RowLogMessage message) throws Exception {
        expectMessage(message, 1);
    }

    public static void expectMessage(RowLogMessage message, int times) throws Exception {
        if (earlyMessages.containsKey(message)) {
            int timesEarlyReceived = earlyMessages.get(message);
            count = count + timesEarlyReceived;
            int remainingTimes = times - timesEarlyReceived;
            if (remainingTimes < 0)
                throw new Exception("Recieved message <" + message + "> more than expected");
            earlyMessages.remove(message);
            if (remainingTimes > 0) {
                expectedMessages.put(message, remainingTimes);
            }
        } else {
            expectedMessages.put(message, times);
        }
    }

    public static void expectMessages(int i) {
        numberOfMessagesToBeExpected = i;
    }

    public boolean processMessage(RowLogMessage message) {
        boolean result;
        if (!expectedMessages.containsKey(message)) {
            if (earlyMessages.containsKey(message)) {
                earlyMessages.put(message, earlyMessages.get(message) + 1);
            } else {
                earlyMessages.put(message, 1);
            }
            result = (!problematicMessages.contains(message));
        } else {
            count++;
            int timesRemaining = expectedMessages.get(message);
            if (timesRemaining == 1) {
                expectedMessages.remove(message);
                result = (!problematicMessages.contains(message));
            } else {
                expectedMessages.put(message, timesRemaining - 1);
                result = false;
            }
        }
        return result;
    }

    public static void waitUntilMessagesConsumed(long timeout) throws Exception {
        long waitUntil = System.currentTimeMillis() + timeout;
        while ((!expectedMessages.isEmpty() || (count < numberOfMessagesToBeExpected))
                && System.currentTimeMillis() < waitUntil) {
            Thread.sleep(100);
        }
    }

    public static void validate() throws Exception {
        Assert.assertFalse("Received less messages <"+count+"> than expected <"+numberOfMessagesToBeExpected+">", (count < numberOfMessagesToBeExpected));
        Assert.assertFalse("Received more messages <"+count+"> than expected <"+numberOfMessagesToBeExpected+">", (count > numberOfMessagesToBeExpected));
        Assert.assertTrue("EarlyMessages list is not empty <"+earlyMessages.keySet()+">", earlyMessages.isEmpty());
        Assert.assertTrue("Expected messages not processed within timeout", expectedMessages.isEmpty());
    }
}