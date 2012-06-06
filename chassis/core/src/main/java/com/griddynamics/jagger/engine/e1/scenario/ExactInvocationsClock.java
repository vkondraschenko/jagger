package com.griddynamics.jagger.engine.e1.scenario;

import com.google.common.collect.Maps;
import com.griddynamics.jagger.coordinator.NodeId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ExactInvocationsClock implements WorkloadClock {

    private static final Logger log = LoggerFactory.getLogger(ExactInvocationsClock.class);

    private int threadCount;

    private int samplesCount;

    private int samplesSubmitted;

    private int totalSamples;

    private int delay;

    private Map<NodeId, WorkloadConfiguration> submittedConfigurations = new HashMap<NodeId, WorkloadConfiguration>();

    public ExactInvocationsClock(int samplesCount, int threadCount, int delay) {
        this.samplesCount = samplesCount;
        this.threadCount  = threadCount;
        this.delay        = delay;
    }

    @Override
    public Map<NodeId, Integer> getPoolSizes(Set<NodeId> nodes) {
        int max = threadCount / nodes.size() + 1;
        Map<NodeId, Integer> result = Maps.newHashMap();
        for (NodeId node : nodes) {
            result.put(node, max);
        }
        return result;
    }

    @Override
    public void tick(WorkloadExecutionStatus status, WorkloadAdjuster adjuster) {
        log.debug("Going to perform tick with status {}", status);

        int samplesPerTick = status.getTotalSamples() - totalSamples;

        totalSamples = status.getTotalSamples();

        int samplesLeft = samplesCount - samplesSubmitted;
        if (samplesLeft <= 0) {
            return;
        }

        if (status.getTotalSamples() < samplesSubmitted / 2) {
            return;
        }

        Set<NodeId> nodes = status.getNodes();
        int threads =  threadCount / nodes.size();
        int samplesToAdd = (samplesLeft < samplesPerTick * 1.5) ? samplesLeft : samplesLeft / 2;
        Map<NodeId, Double>  factors = calculateFactors(status, submittedConfigurations);
        int s = 0;
        for (NodeId node : nodes) {
            int submittedSamplesCount = submittedConfigurations.get(node) != null ? submittedConfigurations.get(node).getSamples() : 0;
            int samples = (int) Math.round(samplesToAdd * factors.get(node)) + submittedSamplesCount;
            
            WorkloadConfiguration workloadConfiguration = WorkloadConfiguration.with(threads, delay, samples);
            adjuster.adjustConfiguration(node, workloadConfiguration);
            s += samples;
            submittedConfigurations.put(node, workloadConfiguration);
        }
        samplesSubmitted = s;
    }

    private Map<NodeId, Double> calculateFactors(WorkloadExecutionStatus status, Map<NodeId, WorkloadConfiguration> configurations) {
        Map<NodeId, Double> result = new HashMap<NodeId, Double>();

        Map<NodeId, Double> scores = new HashMap<NodeId, Double>();
        int nodesCount = status.getNodes().size();
        double scoreSum = 0;
        for (NodeId nodeId: status.getNodes()) {
            double totalSamplesRate = (status.getTotalSamples() == 0) ?
                    (1d / nodesCount) :
                    (double) status.getSamples(nodeId) / status.getTotalSamples();
            
            double ownSamplesRate =  (configurations.get(nodeId) == null || configurations.get(nodeId).getSamples() == 0) ?
                    1d :
                    (double) status.getSamples(nodeId) / configurations.get(nodeId).getSamples();
            
            double score = totalSamplesRate + ownSamplesRate * 10;
            scores.put(nodeId, score);
            scoreSum += score; 
        }
        
        for (NodeId nodeId: status.getNodes()) {
            result.put(nodeId, scores.get(nodeId) / scoreSum);
        }
        return result;        
    }

    @Override
    public int getTickInterval() {
        return 1000;
    }

    @Override
    public int getValue() {
        return samplesCount;
    }

    @Override
    public String toString() {
        return String.format("%d invocations; %d threads; %dms delay", samplesCount, threadCount, delay);
    }
}
