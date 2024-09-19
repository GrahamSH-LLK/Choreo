// Copyright (c) Choreo contributors

package choreo;

import static edu.wpi.first.util.ErrorMessages.requireNonNullParam;

import choreo.autos.AutoChooser;
import choreo.autos.AutoFactory;
import choreo.autos.AutoFactory.ChoreoAutoBindings;
import choreo.autos.AutoLoop;
import choreo.autos.AutoTrajectory;
import choreo.trajectory.ChorTrajectory;
import choreo.trajectory.DiffySample;
import choreo.trajectory.EventMarker;
import choreo.trajectory.ProjectFile;
import choreo.trajectory.SwerveSample;
import choreo.trajectory.TrajSample;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Filesystem;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Subsystem;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Utilities to load and follow Choreo Trajectories */
public final class Choreo {
  private static final Gson GSON = new Gson();
  private static final String TRAJECTORY_FILE_EXTENSION = ".traj";

  private static File CHOREO_DIR = new File(Filesystem.getDeployDirectory(), "choreo");

  private static Optional<ProjectFile> LAZY_PROJECT_FILE = Optional.empty();

  /** This should only be used for unit testing. */
  static void setChoreoDir(File choreoDir) {
    CHOREO_DIR = choreoDir;
  }

  /**
   * Gets the project file from the deploy directory. Choreolib expects a .chor file to be placed in
   * src/main/deploy/choreo.
   *
   * <p>The result is cached after the first call.
   *
   * @return the project file
   */
  public static ProjectFile getProjectFile() {
    if (LAZY_PROJECT_FILE.isPresent()) {
      return LAZY_PROJECT_FILE.get();
    }
    try {
      // find the first file that ends with a .chor extension
      File[] projectFiles = CHOREO_DIR.listFiles((dir, name) -> name.endsWith(".chor"));
      if (projectFiles.length == 0) {
        throw new RuntimeException("Could not find project file in deploy directory");
      } else if (projectFiles.length > 1) {
        throw new RuntimeException("Found multiple project files in deploy directory");
      }
      LAZY_PROJECT_FILE =
          Optional.of(GSON.fromJson(new FileReader(projectFiles[0]), ProjectFile.class));
    } catch (JsonSyntaxException ex) {
      throw new RuntimeException("Could not parse project file", ex);
    } catch (FileNotFoundException ex) {
      throw new RuntimeException("Could not find project file", ex);
    }
    return LAZY_PROJECT_FILE.get();
  }

  /**
   * This interface exists as a type alias. A ControlFunction has a signature of ({@link Pose2d},
   * {@link SampleType})-&gt;{@link ChassisSpeeds}, where the function returns robot-relative {@link
   * ChassisSpeeds} for the robot.
   */
  public interface ControlFunction<SampleType extends TrajSample<SampleType>>
      extends BiFunction<Pose2d, SampleType, ChassisSpeeds> {}

  /**
   * This interface exists as a type alias. A TrajectoryLogger has a signature of ({@link
   * ChorTrajectory}, {@link Boolean})-&gt;void, where the function consumes a trajectory and a
   * boolean indicating whether the trajectory is starting or finishing.
   */
  public interface TrajectoryLogger<SampleType extends TrajSample<SampleType>>
      extends BiConsumer<ChorTrajectory<SampleType>, Boolean> {}

  /** Default constructor. */
  private Choreo() {
    throw new UnsupportedOperationException("This is a utility class!");
  }

  /**
   * Load a trajectory from the deploy directory. Choreolib expects .traj files to be placed in
   * src/main/deploy/choreo/[trajName].traj.
   *
   * @param <SampleType> The type of samples in the trajectory.
   * @param trajName the path name in Choreo, which matches the file name in the deploy directory,
   *     file extension is optional.
   * @return the loaded trajectory, or `Optional.empty()` if the trajectory could not be loaded.
   */
  @SuppressWarnings("unchecked")
  public static <SampleType extends TrajSample<SampleType>>
      Optional<ChorTrajectory<SampleType>> loadTrajectory(String trajName) {
    requireNonNullParam(trajName, "trajName", "Choreo.loadTrajectory");

    if (trajName.endsWith(TRAJECTORY_FILE_EXTENSION)) {
      trajName = trajName.substring(0, trajName.length() - TRAJECTORY_FILE_EXTENSION.length());
    }
    File trajFile = new File(CHOREO_DIR, trajName + TRAJECTORY_FILE_EXTENSION);
    try {
      var reader = new BufferedReader(new FileReader(trajFile));
      String str = reader.lines().reduce("", (a, b) -> a + b);
      reader.close();
      ChorTrajectory<SampleType> traj =
          (ChorTrajectory<SampleType>) readTrajectoryString(str, getProjectFile());
      return Optional.of(traj);
    } catch (FileNotFoundException ex) {
      DriverStation.reportError("Could not find trajectory file: " + trajFile, false);
    } catch (JsonSyntaxException ex) {
      DriverStation.reportError("Could not parse trajectory file: " + trajFile, false);
    } catch (Exception ex) {
      DriverStation.reportError(ex.getMessage(), ex.getStackTrace());
    }
    return Optional.empty();
  }

  static ChorTrajectory<? extends TrajSample<?>> readTrajectoryString(
      String str, ProjectFile projectFile) {
    JsonObject wholeTraj = GSON.fromJson(str, JsonObject.class);
    String name = wholeTraj.get("name").getAsString();
    EventMarker[] events = GSON.fromJson(wholeTraj.get("events"), EventMarker[].class);
    JsonObject trajObj = wholeTraj.getAsJsonObject("traj");
    Integer[] splits = GSON.fromJson(trajObj.get("splits"), Integer[].class);
    if (projectFile.type.equals("Swerve")) {
      SwerveSample[] samples = GSON.fromJson(trajObj.get("samples"), SwerveSample[].class);
      return new ChorTrajectory<SwerveSample>(
          name, List.of(samples), List.of(splits), List.of(events));
    } else if (projectFile.type.equals("Differential")) {
      DiffySample[] sampleArray = GSON.fromJson(trajObj.get("samples"), DiffySample[].class);
      return new ChorTrajectory<DiffySample>(
          name, List.of(sampleArray), List.of(splits), List.of(events));
    } else {
      throw new RuntimeException("Unknown project type: " + projectFile.type);
    }
  }

  /**
   * A utility for caching loaded trajectories. This allows for loading trajectories only once, and
   * then reusing them.
   */
  public static class ChoreoTrajCache {
    private final Map<String, ChorTrajectory<?>> cache;

    /** Creates a new ChoreoTrajCache with a normal {@link HashMap} as the cache. */
    public ChoreoTrajCache() {
      cache = new HashMap<>();
    }

    /**
     * Creates a new ChoreoTrajCache with a custom cache.
     *
     * <p>this could be useful if you want to use a concurrent map or a map with a maximum size.
     *
     * @param cache The cache to use.
     */
    public ChoreoTrajCache(Map<String, ChorTrajectory<?>> cache) {
      requireNonNullParam(cache, "cache", "ChoreoTrajCache.<init>");
      this.cache = cache;
    }

    /**
     * Load a trajectory from the deploy directory. Choreolib expects .traj files to be placed in
     * src/main/deploy/choreo/[trajName].traj.
     *
     * <p>This method will cache the loaded trajectory and reused it if it is requested again.
     *
     * @param trajName the path name in Choreo, which matches the file name in the deploy directory,
     *     file extension is optional.
     * @return the loaded trajectory, or `Optional.empty()` if the trajectory could not be loaded.
     * @see Choreo#loadTrajectory(String)
     */
    public Optional<? extends ChorTrajectory<?>> loadTrajectory(String trajName) {
      requireNonNullParam(trajName, "trajName", "ChoreoTrajCache.loadTrajectory");
      if (cache.containsKey(trajName)) {
        return Optional.of(cache.get(trajName));
      } else {
        return loadTrajectory(trajName)
            .map(
                traj -> {
                  cache.put(trajName, traj);
                  return traj;
                });
      }
    }

    /**
     * Load a section of a split trajectory from the deploy directory. Choreolib expects .traj files
     * to be placed in src/main/deploy/choreo/[trajName].traj.
     *
     * <p>This method will cache the loaded trajectory and reused it if it is requested again. The
     * trajectory that is split off of will also be cached.
     *
     * @param trajName the path name in Choreo, which matches the file name in the deploy directory,
     *     file extension is optional.
     * @param splitIndex the index of the split trajectory to load
     * @return the loaded trajectory, or `Optional.empty()` if the trajectory could not be loaded.
     * @see Choreo#loadTrajectory(String)
     */
    public Optional<? extends ChorTrajectory<?>> loadTrajectory(String trajName, int splitIndex) {
      requireNonNullParam(trajName, "trajName", "ChoreoTrajCache.loadTrajectory");
      // make the key something that could never possibly be a valid trajectory name
      String key = trajName + ".:." + splitIndex;
      if (cache.containsKey(key)) {
        return Optional.of(cache.get(key));
      } else if (cache.containsKey(trajName)) {
        return cache
            .get(trajName)
            .getSplit(splitIndex)
            .map(
                traj -> {
                  cache.put(key, traj);
                  return traj;
                });
      } else {
        return loadTrajectory(trajName)
            .flatMap(
                traj -> {
                  cache.put(trajName, traj);
                  return traj.getSplit(splitIndex)
                      .map(
                          split -> {
                            cache.put(key, split);
                            return split;
                          });
                });
      }
    }

    /** Clear the cache. */
    public void clear() {
      cache.clear();
    }
  }

  /**
   * Create a factory that can be used to create {@link AutoLoop} and {@link AutoTrajectory}.
   *
   * @param <SampleType> The type of samples in the trajectory.
   * @param driveSubsystem The drive {@link Subsystem} to require for {@link AutoTrajectory} {@link
   *     Command}s.
   * @param poseSupplier A function that returns the current field-relative {@link Pose2d} of the
   *     robot.
   * @param controller A {@link ControlFunction} to follow the current {@link
   *     ChorTrajectory}&lt;{@link SampleType}&gt;.
   * @param outputChassisSpeeds A function that consumes the target robot-relative {@link
   *     ChassisSpeeds} and commands them to the robot.
   * @param mirrorTrajectory If this returns true, the path will be mirrored to the opposite side,
   *     while keeping the same coordinate system origin. This will be called every loop during the
   *     command.
   * @param bindings Universal trajectory event bindings.
   * @return An {@link AutoFactory} that can be used to create {@link AutoLoop} and {@link
   *     AutoTrajectory}.
   * @see AutoChooser using this factory with AutoChooser to generate auto routines.
   */
  public static <SampleType extends TrajSample<SampleType>> AutoFactory createAutoFactory(
      Subsystem driveSubsystem,
      Supplier<Pose2d> poseSupplier,
      ControlFunction<SampleType> controller,
      Consumer<ChassisSpeeds> outputChassisSpeeds,
      BooleanSupplier mirrorTrajectory,
      ChoreoAutoBindings bindings) {
    return new AutoFactory(
        requireNonNullParam(poseSupplier, "poseSupplier", "Choreo.createAutoFactory"),
        requireNonNullParam(controller, "controller", "Choreo.createAutoFactory"),
        requireNonNullParam(outputChassisSpeeds, "outputChassisSpeeds", "Choreo.createAutoFactory"),
        requireNonNullParam(mirrorTrajectory, "mirrorTrajectory", "Choreo.createAutoFactory"),
        requireNonNullParam(driveSubsystem, "driveSubsystem", "Choreo.createAutoFactory"),
        requireNonNullParam(bindings, "bindings", "Choreo.createAutoFactory"),
        Optional.empty());
  }

  /**
   * Create a factory that can be used to create {@link AutoLoop} and {@link AutoTrajectory}.
   *
   * @param <SampleType> The type of samples in the trajectory.
   * @param driveSubsystem The drive {@link Subsystem} to require for {@link AutoTrajectory} {@link
   *     Command}s.
   * @param poseSupplier A function that returns the current field-relative {@link Pose2d} of the
   *     robot.
   * @param controller A {@link ControlFunction} to follow the current {@link
   *     ChorTrajectory}&lt;{@link SampleType}&gt;.
   * @param outputChassisSpeeds A function that consumes the target robot-relative {@link
   *     ChassisSpeeds} and commands them to the robot.
   * @param mirrorTrajectory If this returns true, the path will be mirrored to the opposite side,
   *     while keeping the same coordinate system origin. This will be called every loop during the
   *     command.
   * @param bindings Universal trajectory event bindings.
   * @param trajLogger A {@link TrajectoryLogger} to log {@link ChorTrajectory} as they start and
   *     finish.
   * @return An {@link AutoFactory} that can be used to create {@link AutoLoop} and {@link
   *     AutoTrajectory}.
   * @see AutoChooser using this factory with AutoChooser to generate auto routines.
   */
  public static <SampleType extends TrajSample<SampleType>> AutoFactory createAutoFactory(
      Subsystem driveSubsystem,
      Supplier<Pose2d> poseSupplier,
      ControlFunction<SampleType> controller,
      Consumer<ChassisSpeeds> outputChassisSpeeds,
      BooleanSupplier mirrorTrajectory,
      ChoreoAutoBindings bindings,
      TrajectoryLogger<SampleType> trajLogger) {
    return new AutoFactory(
        requireNonNullParam(poseSupplier, "poseSupplier", "Choreo.createAutoFactory"),
        requireNonNullParam(controller, "controller", "Choreo.createAutoFactory"),
        requireNonNullParam(outputChassisSpeeds, "outputChassisSpeeds", "Choreo.createAutoFactory"),
        requireNonNullParam(mirrorTrajectory, "mirrorTrajectory", "Choreo.createAutoFactory"),
        requireNonNullParam(driveSubsystem, "driveSubsystem", "Choreo.createAutoFactory"),
        requireNonNullParam(bindings, "bindings", "Choreo.createAutoFactory"),
        Optional.of(trajLogger));
  }
}
