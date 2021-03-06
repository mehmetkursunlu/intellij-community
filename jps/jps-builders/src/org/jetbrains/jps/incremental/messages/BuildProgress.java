// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.jps.incremental.messages;

import com.intellij.openapi.diagnostic.Logger;
import gnu.trove.TObjectIntHashMap;
import gnu.trove.TObjectLongHashMap;
import org.jetbrains.jps.builders.BuildTarget;
import org.jetbrains.jps.builders.BuildTargetIndex;
import org.jetbrains.jps.builders.BuildTargetType;
import org.jetbrains.jps.builders.impl.BuildTargetChunk;
import org.jetbrains.jps.incremental.BuilderRegistry;
import org.jetbrains.jps.incremental.CompileContext;
import org.jetbrains.jps.incremental.FSOperations;
import org.jetbrains.jps.incremental.Utils;
import org.jetbrains.jps.incremental.storage.BuildDataManager;
import org.jetbrains.jps.incremental.storage.BuildTargetsState;

import java.util.*;
import java.util.function.Predicate;

/**
 * Estimates build time to provide information about build progress.
 * <p>
 * During build it remembers time required to fully rebuild a {@link BuildTarget} from scratch and computes average time for all targets
 * of each {@link BuildTargetType}. This information is used in subsequent builds to estimate what part of work is done.
 * </p>
 * <p>
 * For incremental build it'll take less time to build each target. However we assume that that time is proportional to time required to
 * fully rebuild a target from scratch and compute the progress accordingly.
 * </p>
 */
public class BuildProgress {
  private static final Logger LOG = Logger.getInstance(BuildProgress.class);
  private final BuildDataManager myDataManager;
  private final BuildTargetIndex myTargetIndex;
  private final TObjectIntHashMap<BuildTargetType<?>> myNumberOfFinishedTargets = new TObjectIntHashMap<>();
  private final TObjectLongHashMap<BuildTargetType<?>> myExpectedBuildTimeForTarget = new TObjectLongHashMap<>();
  /** sum of expected build time for all affected targets */
  private final long myExpectedTotalTime;
  /** maps a currently building target to part of work which was done for this target (value between 0.0 and 1.0) */
  private final Map<BuildTarget, Double> myCurrentProgress = new HashMap<>();
  /** sum of expected build time for all finished targets */
  private long myExpectedTimeForFinishedTargets;

  private final TObjectIntHashMap<BuildTargetType<?>> myTotalTargets = new TObjectIntHashMap<>();
  private final TObjectLongHashMap<BuildTargetType<?>> myTotalBuildTimeForFullyRebuiltTargets = new TObjectLongHashMap<>();
  private final TObjectIntHashMap<BuildTargetType<?>> myNumberOfFullyRebuiltTargets = new TObjectIntHashMap<>();


  public BuildProgress(BuildDataManager dataManager, BuildTargetIndex targetIndex, List<BuildTargetChunk> allChunks, Predicate<BuildTargetChunk> isAffected) {
    myDataManager = dataManager;
    myTargetIndex = targetIndex;
    Set<BuildTargetType<?>> targetTypes = new LinkedHashSet<>();
    TObjectIntHashMap<BuildTargetType<?>> totalAffectedTargets = new TObjectIntHashMap<>();
    for (BuildTargetChunk chunk : allChunks) {
      boolean affected = isAffected.test(chunk);
      for (BuildTarget<?> target : chunk.getTargets()) {
        if (!targetIndex.isDummy(target)) {
          if (affected) {
            increment(totalAffectedTargets, target.getTargetType());
            targetTypes.add(target.getTargetType());
          }
          increment(myTotalTargets, target.getTargetType());
        }
      }
    }

    long expectedTotalTime = 0;
    for (BuildTargetType<?> targetType : targetTypes) {
      myExpectedBuildTimeForTarget.put(targetType, myDataManager.getTargetsState().getAverageBuildTime(targetType));
    }
    for (BuildTargetType<?> type : targetTypes) {
      if (myExpectedBuildTimeForTarget.get(type) == -1) {
        myExpectedBuildTimeForTarget.put(type, computeExpectedTimeBasedOnOtherTargets(type, targetTypes, myExpectedBuildTimeForTarget));
      }
    }
    for (BuildTargetType<?> targetType : targetTypes) {
      expectedTotalTime += myExpectedBuildTimeForTarget.get(targetType) * totalAffectedTargets.get(targetType);
    }
    myExpectedTotalTime = Math.max(expectedTotalTime, 1);
    if (LOG.isDebugEnabled()) {
      LOG.debug("expected total time is " + myExpectedTotalTime);
      for (BuildTargetType<?> type : targetTypes) {
        LOG.debug(" expected build time for " + type.getTypeId() + " is " + myExpectedBuildTimeForTarget.get(type));
      }
    }
  }

  /**
   * If there is no information about average build time for any {@link BuildTargetType} returns {@link BuilderRegistry#getExpectedBuildTimeForTarget the default expected value}.
   * Otherwise estimate build time using real average time for other targets and ratio between the default expected times.
   */
  private static long computeExpectedTimeBasedOnOtherTargets(BuildTargetType<?> type, Set<BuildTargetType<?>> allTypes,
                                                             TObjectLongHashMap<BuildTargetType<?>> expectedBuildTimeForTarget) {
    BuilderRegistry registry = BuilderRegistry.getInstance();
    int baseTargetsCount = 0;
    long expectedTimeSum = 0;
    for (BuildTargetType<?> anotherType : allTypes) {
      long realExpectedTime = expectedBuildTimeForTarget.get(anotherType);
      long defaultExpectedTime = registry.getExpectedBuildTimeForTarget(anotherType);
      if (realExpectedTime != -1 && defaultExpectedTime > 0) {
        baseTargetsCount++;
        expectedTimeSum += realExpectedTime * registry.getExpectedBuildTimeForTarget(type) / defaultExpectedTime;
      }
    }
    return baseTargetsCount != 0 ? expectedTimeSum/baseTargetsCount : registry.getExpectedBuildTimeForTarget(type);
  }

  private synchronized void notifyAboutTotalProgress(CompileContext context) {
    long expectedTimeForFinishedWork = myExpectedTimeForFinishedTargets;
    for (Map.Entry<BuildTarget, Double> entry : myCurrentProgress.entrySet()) {
      expectedTimeForFinishedWork += myExpectedBuildTimeForTarget.get(entry.getKey().getTargetType()) * entry.getValue();
    }
    float done = ((float)expectedTimeForFinishedWork) / myExpectedTotalTime;
    context.setDone(done);
  }

  public synchronized void updateProgress(BuildTarget target, double done, CompileContext context) {
    myCurrentProgress.put(target, done);
    notifyAboutTotalProgress(context);
  }

  public synchronized void onTargetChunkFinished(BuildTargetChunk chunk, CompileContext context) {
    boolean successful = !Utils.errorsDetected(context) && !context.getCancelStatus().isCanceled();
    for (BuildTarget<?> target : chunk.getTargets()) {
      myCurrentProgress.remove(target);
      if (!myTargetIndex.isDummy(target)) {
        BuildTargetType<?> targetType = target.getTargetType();
        increment(myNumberOfFinishedTargets, targetType);
        myExpectedTimeForFinishedTargets += myExpectedBuildTimeForTarget.get(targetType);

        if (successful && FSOperations.isMarkedDirty(context, target)) {
          long elapsedTime = System.currentTimeMillis() - context.getCompilationStartStamp(target);
          long buildTime = elapsedTime / chunk.getTargets().size();
          if (!myTotalBuildTimeForFullyRebuiltTargets.adjustValue(targetType, buildTime)) {
            myTotalBuildTimeForFullyRebuiltTargets.put(targetType, buildTime);
          }
          increment(myNumberOfFullyRebuiltTargets, targetType);
        }
      }
    }
    notifyAboutTotalProgress(context);
  }

  public void updateExpectedAverageTime() {
    LOG.debug("update expected build time for " + myTotalBuildTimeForFullyRebuiltTargets.size() + " target types");
    myTotalBuildTimeForFullyRebuiltTargets.forEachEntry((type, totalTime) -> {
      BuildTargetsState targetsState = myDataManager.getTargetsState();
      long oldAverageTime = targetsState.getAverageBuildTime(type);
      long newAverageTime;
      if (oldAverageTime == -1) {
        newAverageTime = totalTime / myNumberOfFullyRebuiltTargets.get(type);
      }
      else {
        //if not all targets of this type were fully rebuilt, we assume that old average value is still actual for them; this way we won't get incorrect value if only one small target was fully rebuilt
        newAverageTime = (totalTime + (myTotalTargets.get(type) - myNumberOfFullyRebuiltTargets.get(type)) * oldAverageTime) / myTotalTargets.get(type);
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug(" " + type.getTypeId() + ": old=" + oldAverageTime + ", new=" + newAverageTime + " (based on " + myNumberOfFullyRebuiltTargets.get(type)
                  + " of " + myTotalTargets.get(type) + " targets)");
      }
      targetsState.setAverageBuildTime(type, newAverageTime);
      return true;
    });
  }

  private static <T> void increment(TObjectIntHashMap<T> map, T key) {
    if (!map.increment(key)) {
      map.put(key, 1);
    }
  }
}
