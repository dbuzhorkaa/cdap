package com.continuuity.performance.benchmark;

import com.continuuity.common.conf.CConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class BenchmarkRunner {

  private static final Logger LOG = LoggerFactory.getLogger(BenchmarkRunner.class);

//  private Object notificationMonitor = new Object();
  private final String notifyingAgentMonitor = new String("monitor");
  private boolean[] finishedAgents;

  String benchName = null;
  Benchmark benchmark = null;
  CConfiguration config = CConfiguration.create();

  void usage() {
    System.out.println("Usage: BenchmarkRunner --bench <name> [ --report " + "<seconds> ] [ --<key> <value> ... ]");
    if (benchmark != null) {
      Map<String, String> usage = benchmark.usage();
      if (usage != null && !usage.isEmpty()) {
        LOG.info("Specific options for benchmark " + benchName + ":");
        for (String option : usage.keySet()) {
          LOG.info(String.format("  %-20s %s", option, usage.get(option)));
        }
      }
    } else {
      System.out.println("Use --help --bench <name> for benchmark specific " + "options.");
    }
  }

  protected void agentFinished(int agentId) {
    finishedAgents[agentId] = true;
    synchronized (notifyingAgentMonitor) {
      notifyingAgentMonitor.notify();
    }
  }

  boolean parseOptions(String[] args) throws BenchmarkException {
    boolean help = false;

    // 1. parse command line for --bench, copy everything else into config
    LOG.debug("Parsing command line options...");
    for (int i = 0; i < args.length; i++) {
      if ("--help".equals(args[i])) {
        help = true;
        continue;
      }
      else if (args[i].startsWith("--")) {
        if (i + 1 < args.length) {
          String key = args[i].substring(2);
          String value = args[++i];
          config.set(key, value);
          if ("bench".equals(key)) {
            benchName = value;
          }
        } else {
          throw new BenchmarkException("--<key> must have an argument.");
        }
      }
    }

    LOG.debug("Instantiating and configuring benchmark...");
    // 2. instantiate benchmark and configure it
    if (benchName == null) {
      if (help) {
        usage();
        return false;
      } else {
        throw new BenchmarkException("--bench must be specified.");
      }
    }
    if (!benchName.startsWith("com.continuuity")) {
      benchName = this.getClass().getPackage().getName() + "." + benchName;
    }
    try {
      benchmark = (Benchmark)Class.forName(benchName).newInstance();
    } catch (Exception e) {
      throw new BenchmarkException("Unable to instantiate benchmark '" +
          benchName + "': " + e.getMessage(), e);
    }
    if (help) {
      usage();
      benchmark = null;
      return false;
    }
    benchmark.configure(config);
    return true;
  }

  private void waitForBenchmarkThreadsToFinish(long time) {
    synchronized(notifyingAgentMonitor) {
      try {
        notifyingAgentMonitor.wait(time);
      } catch (InterruptedException e) {
        LOG.debug("InterruptedException caught when waiting for benchmark threads to finish. Ignoring.");
      }
    }
  }

  boolean run() throws BenchmarkException {
    // 1. initialize benchmark

    LOG.debug("Executing benchmark.initialize()");
    benchmark.initialize();

    // 2. warm up benchmark
    LOG.debug("Executing benchmark.warmup()");
    benchmark.warmup();

    // 3. get agent groups and create a thread for each agent
    AgentGroup[] groups = benchmark.getAgentGroups();
    BenchmarkMetric[] groupMetrics = new BenchmarkMetric[groups.length];
    LinkedList<BenchmarkThread> threadList = new LinkedList<BenchmarkThread>();
    Map<Integer, BenchmarkThread> threadMap = new HashMap<Integer, BenchmarkThread>();

    LOG.debug("Executing benchmark.warmup()");
    for (int j = 0; j < groups.length; j++) {
      AgentGroup group = groups[j];
      int numAgents = group.getNumAgents();
      if (numAgents < 1) {
        throw new BenchmarkException("Number of agents for group " + group
            .getName() + " must be at leat one but is " + numAgents + ".");
      }
      int runsPerAgent = group.getTotalRuns() / numAgents;
      LOG.info("Running " + numAgents + " " + group.getName() + " agents (" +
                 (runsPerAgent > 0 ? Integer.toString(runsPerAgent) : "unlimited") + " runs per agent, " +
                 (group.getSecondsToRun() > 0 ? Integer.toString(group.getSecondsToRun()) + " seconds" : "no") + " " +
                 "time limit, " +
                 (group.getRunsPerSecond() > 0 ? "max " + Integer.toString(group.getRunsPerSecond()) : "unlimited") +
                 " runs per second).");

      groupMetrics[j] = new BenchmarkMetric();
      for (int i = 0; i < group.getNumAgents(); ++i) {
        BenchmarkThread bt = new BenchmarkThread(this, group, i, groupMetrics[j]);
        threadList.add(bt);
        threadMap.put(i, bt);
      }
    }

    finishedAgents = new boolean[threadList.size()];

    BenchmarkThread[] threads =
        threadList.toArray(new BenchmarkThread[threadList.size()]);
    ReportThread consoleReporter = new ReportConsoleThread(groups, groupMetrics, config);

    // 4. start the console and other reporter threads
    LOG.debug("Starting console reporter thread");
    consoleReporter.start();

    ReportThread fileReporter = null;
    String reportFile = config.get("reportfile");
    if (reportFile != null && reportFile.length() != 0) {
      fileReporter = new ReportFileAppenderThread(benchName, groups, groupMetrics, config);
      LOG.debug("Starting file reporter thread");
      fileReporter.start();
    }

    ReportThread mensaReporter = null;
    String mensa = config.get("mensa");
    if (mensa != null && mensa.length() != 0) {
      mensaReporter = new ReportMensaWriterThread(benchName, groups, groupMetrics, config, "");
      LOG.debug("Starting mensa reporter thread");
      mensaReporter.start();
    }

    // 5. start all benchmark threads
    for (int i=0; i<threads.length; i++) {
      LOG.debug("Starting benchmark thread {}", i);
      threads[i].start();
    }

    // 6. wait for all threads to finish
    int threadsFinished=0;
    LOG.debug("Waiting for all {} benchmark threads to finish...", threadList.size());
    while (!threadMap.isEmpty()) {
      waitForBenchmarkThreadsToFinish(60000);
      for (int i=0; i < finishedAgents.length; i++) {
        if (finishedAgents[i] == true) {
          BenchmarkThread thread = threadMap.get(i);
          try {
            thread.join(10);
          } catch (InterruptedException e) {
            LOG.debug("InterruptedException when trying to wait for a benchmark thread to finish. Ignoring.");
          }
          if (!thread.isAlive()) {
            finishedAgents[i] = false;
            threadMap.remove(i);
            LOG.debug("Another benchmark thread finished. {} benchmark threads are still running.", threadMap.size());
            threadsFinished++;
            if (threadsFinished == 1) {
              LOG.debug("Stopping console reporter thread...");
              stopReporterThread(consoleReporter);
              if (fileReporter != null) {
                LOG.debug("Stopping file reporter thread...");
                stopReporterThread(fileReporter);
              }
              if (mensaReporter != null) {
                LOG.debug("Stopping mensa reporter thread...");
                stopReporterThread(mensaReporter);
              }
            }
          }
        }
      }
    }
    LOG.debug("All benchmark threads stopped.");

//    Thread.interrupted();

    // 7. Stop reporter thread if still running
    stopReporterThread(consoleReporter);
    stopReporterThread(fileReporter);
    stopReporterThread(mensaReporter);
    return true;
  }

  void shutdown() throws BenchmarkException {
    if (benchmark != null) {
      LOG.debug("Executing benchmark.shutdown()");
      benchmark.shutdown();
    }
  }

  private void stopReporterThread(ReportThread reporter) {
    if (reporter!= null && !reporter.isAlive()) return;
    reporter.interrupt();
    while (reporter.isAlive()) {
      try {
        reporter.join();
      } catch (InterruptedException e) {
        LOG.debug("InterruptedException caught in thread.join() when trying to stop reporter thread.");
      }
    }
  }

  public static void main(String[] args) throws Exception {
    // create a runner
    BenchmarkRunner runner = new BenchmarkRunner();

    try {
      // configure it
      boolean ok = runner.parseOptions(args);

      // run it
      if (ok) runner.run();
    } catch (Exception e) {
      LOG.error(e.getMessage());
      throw e;
    } finally {
      // shut it down
      try {
        runner.shutdown();
      } catch (Exception e) {
        LOG.error(e.getMessage());
        throw e;
      }
    }
  }
}
