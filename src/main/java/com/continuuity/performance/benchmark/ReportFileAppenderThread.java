package com.continuuity.performance.benchmark;

import com.continuuity.common.conf.CConfiguration;
import com.continuuity.performance.util.MensaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReportFileAppenderThread extends ReportThread {

  private static final Logger LOG = LoggerFactory.getLogger(ReportFileAppenderThread.class);
  private static final String OPS_PER_SEC_ONE_MIN = "benchmark.ops.per_sec.1m";
  private static final String OPS_PER_SEC_AVG = "benchmark.ops.per_sec.avg";

  String fileName;
  File reportFile;
  String benchmarkName;
  Map<String, ArrayList<Double>> metrics;
  BufferedWriter bw;

  public ReportFileAppenderThread(String benchmarkName, AgentGroup[] groups, BenchmarkMetric[] metrics,
                                  CConfiguration config) {
    this.groupMetrics = metrics;
    this.groups = groups;
    this.reportInterval = config.getInt("report", reportInterval);
    this.fileName = config.get("reportfile");
    int pos=benchmarkName.lastIndexOf(".");
    if (pos!=-1) {
      this.benchmarkName=benchmarkName.substring(pos + 1, benchmarkName.length());
    } else {
      this.benchmarkName=benchmarkName;
    }
    this.metrics = new HashMap<String,ArrayList<Double>>(groups.length);
    for (int i=0; i<groups.length; i++) {
      this.metrics.put(groups[i].getName(), new ArrayList<Double>());
    }
  }

  @Override
  protected void init() {
    reportFile = new File(fileName);
    try {
      bw = new BufferedWriter(new FileWriter(reportFile, true));
    } catch (IOException e) {
      LOG.error("Error during init.", e);
    }
  }

  @Override
  public void processGroupMetricsInterval(long unixTime,
                                          AgentGroup group,
                                          long previousMillis,
                                          long millis,
                                          Map<String, Long> prevMetrics,
                                          Map<String, Long> latestMetrics,
                                          boolean interrupt) {
    if (prevMetrics != null) {
      for (Map.Entry<String, Long> singleMetric : prevMetrics.entrySet()) {
        String key = singleMetric.getKey();
        long value = singleMetric.getValue();
        if (!interrupt) {
          Long previousValue = prevMetrics.get(key);
          if (previousValue == null) previousValue = 0L;
          long valueSince = value - previousValue;
          long millisSince = millis - previousMillis;
          metrics.get(group.getName()).add(valueSince * 1000.0 / millisSince);
          String metricValue = String.format("%1.2f", valueSince * 1000.0 / millisSince);
          String metric = MensaUtils.buildMetric(OPS_PER_SEC_ONE_MIN, Long.toString(unixTime), metricValue,
                                                 benchmarkName, group.getName(),
                                                 Integer.toString(group.getNumAgents()), "");
          LOG.info("Collected metric {} in memory ", metric);
        }
      }
    }
  }


  @Override
  protected void processGroupMetricsFinal(long unixTime, AgentGroup group) {
    List<Double> grpVals = metrics.get(group.getName());
    if (grpVals.size() != 0 ) {
      double sum = 0;
      for (int j = 0; j < grpVals.size(); j++) {
        sum += grpVals.get(j);
      }
      double avg = sum / grpVals.size();
      String metricValue = String.format("%1.2f", avg);
      String metric = MensaUtils.buildMetric(OPS_PER_SEC_AVG,
                                             Long.toString(unixTime),
                                             metricValue,
                                             benchmarkName,
                                             group.getName(),
                                             Integer.toString(group.getNumAgents()),
                                             "");
      LOG.info("Writing {} to file {}", metric, fileName);
      try {
        bw.write(metric);
        bw.write("\n");
        bw.flush();
      } catch (IOException e) {
        LOG.error("Error during processing of final group metrics", e);
      }
    }
  }

  @Override
  protected void shutdown() {
    if (bw == null) return;
    try {
      bw.close();
    } catch (IOException e) {
    }
  }
}
