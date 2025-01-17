// Copyright 2021-2023 FRC 6328
// http://github.com/Mechanical-Advantage
//
// This program is free software; you can redistribute it and/or
// modify it under the terms of the GNU General Public License
// version 3 as published by the Free Software Foundation or
// available in the root directory of this project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.

package frc.robot.subsystems.swerve;

import static edu.wpi.first.units.Units.Seconds;
import static edu.wpi.first.units.Units.Volts;

import com.choreo.lib.Choreo;
import com.choreo.lib.ChoreoTrajectoryState;
import com.ctre.phoenix6.SignalLogger;
import com.google.common.collect.Streams;
import com.pathplanner.lib.auto.AutoBuilder;
import com.pathplanner.lib.util.HolonomicPathFollowerConfig;
import com.pathplanner.lib.util.PIDConstants;
import com.pathplanner.lib.util.PathPlannerLogging;
import com.pathplanner.lib.util.ReplanningConfig;
import edu.wpi.first.math.controller.ProfiledPIDController;
import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Transform2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.math.geometry.Twist2d;
import edu.wpi.first.math.kinematics.ChassisSpeeds;
import edu.wpi.first.math.kinematics.SwerveDriveKinematics;
import edu.wpi.first.math.kinematics.SwerveDriveOdometry;
import edu.wpi.first.math.kinematics.SwerveModulePosition;
import edu.wpi.first.math.kinematics.SwerveModuleState;
import edu.wpi.first.math.trajectory.TrapezoidProfile.Constraints;
import edu.wpi.first.math.trajectory.TrapezoidProfile.State;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.units.Measure;
import edu.wpi.first.units.Voltage;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine.Direction;
import frc.robot.FieldConstants;
import frc.robot.subsystems.swerve.Module.ModuleConstants;
import frc.robot.utils.autoaim.AutoAim;
import frc.robot.utils.autoaim.ShotData;
import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.littletonrobotics.junction.AutoLogOutput;
import org.littletonrobotics.junction.Logger;

public class SwerveSubsystem extends SubsystemBase {

  public class AutoAimStates {

    public static ShotData curShotData = new ShotData(new Rotation2d(), 0, 0, 0);
    public static ChassisSpeeds curShotSpeeds = new ChassisSpeeds();
    public static Pose2d endingPose = new Pose2d();
    public static double polarVelocity = 0.0;
    public static Pose2d virtualTarget = new Pose2d();
    public static double polarRadians = 0.0;
    public static ChassisSpeeds inputSpeeds = new ChassisSpeeds(0, 0, 0);
    public static Rotation2d rotationsToTranslation = new Rotation2d();
    public static ChoreoTrajectoryState curState = null;
    public static double elapsedAutonomousSeconds = 0;
    public static double startingAutonomousSeconds = 0;
    public static double pathTotalTime = 0;
    public static String pathName = "";
  }

  // Drivebase constants
  public static final double MAX_LINEAR_SPEED = Units.feetToMeters(12.5);
  public static final double TRACK_WIDTH_X = Units.inchesToMeters(21.75);
  public static final double TRACK_WIDTH_Y = Units.inchesToMeters(21.25);
  public static final double DRIVE_BASE_RADIUS =
      Math.hypot(TRACK_WIDTH_X / 2.0, TRACK_WIDTH_Y / 2.0);
  public static final double MAX_ANGULAR_SPEED = MAX_LINEAR_SPEED / DRIVE_BASE_RADIUS;
  // Hardware constants
  public static final int PIGEON_ID = 0;

  public static final ModuleConstants frontLeft =
      new ModuleConstants("Front Left", 0, 1, 0, Rotation2d.fromRotations(0.0));
  public static final ModuleConstants frontRight =
      new ModuleConstants("Front Right", 2, 3, 1, Rotation2d.fromRotations(0.0));
  public static final ModuleConstants backLeft =
      new ModuleConstants("Back Left", 4, 5, 2, Rotation2d.fromRotations(0.0));
  public static final ModuleConstants backRight =
      new ModuleConstants("Back Right", 6, 7, 3, Rotation2d.fromRotations(0.0));

  public static final Lock odometryLock = new ReentrantLock();
  private final GyroIO gyroIO;
  private final GyroIOInputsAutoLogged gyroInputs = new GyroIOInputsAutoLogged();
  private final Module[] modules; // FL, FR, BL, BR

  private SwerveDriveKinematics kinematics = new SwerveDriveKinematics(getModuleTranslations());
  private Pose2d pose = new Pose2d();
  private Rotation2d lastGyroRotation = new Rotation2d();
  private SwerveDriveOdometry odometry;

  private final SysIdRoutine moduleSteerRoutine;
  private final SysIdRoutine driveRoutine;

  public SwerveSubsystem(GyroIO gyroIO, ModuleIO... moduleIOs) {
    this.gyroIO = gyroIO;
    new AutoAim();
    modules = new Module[moduleIOs.length];

    for (int i = 0; i < moduleIOs.length; i++) {
      modules[i] = new Module(moduleIOs[i]);
    }

    AutoBuilder.configureHolonomic(
        this::getPose, // Robot pose supplier
        this::setPose, // Method to reset odometry (will be called if your auto has a starting pose)
        this::getRobotRelativeSpeeds, // ChassisSpeeds supplier. MUST BE ROBOT RELATIVE
        this::runVelocity, // Method that will drive the robot given ROBOT RELATIVE
        // ChassisSpeeds
        new HolonomicPathFollowerConfig( // HolonomicPathFollowerConfig, this should likely live in
            // your Constants class
            new PIDConstants(5.0, 0.0, 0.0), // Translation PID constants
            new PIDConstants(5.0, 0.0, 0.0), // Rotation PID constants
            MAX_LINEAR_SPEED, // Max module speed, in m/s
            DRIVE_BASE_RADIUS, // Drive base radius in meters. Distance from robot center to
            // furthest module.
            new ReplanningConfig(
                false, false) // Default path replanning config. See the API for the options
            // here
            ),
        () -> false,
        this // Reference to this subsystem to set requirements
        );

    PathPlannerLogging.setLogTargetPoseCallback(
        (pose) -> {
          Logger.recordOutput("PathPlanner/Target", pose);
          Logger.recordOutput(
              "PathPlanner/Absolute Translation Error",
              pose.minus(getPose()).getTranslation().getNorm());
        });
    PathPlannerLogging.setLogActivePathCallback(
        (path) -> Logger.recordOutput("PathPlanner/Active Path", path.toArray(Pose2d[]::new)));
    Logger.recordOutput("PathPlanner/Target", new Pose2d());
    Logger.recordOutput("PathPlanner/Absolute Translation Error", 0.0);

    odometry = new SwerveDriveOdometry(kinematics, getRotation(), getModulePositions());

    moduleSteerRoutine =
        new SysIdRoutine(
            new SysIdRoutine.Config(
                null, // Default ramp rate is acceptable
                Volts.of(8),
                Seconds.of(6.0), // Default timeout is acceptable
                // Log state with Phoenix SignalLogger class
                (state) -> SignalLogger.writeString("state", state.toString())),
            new SysIdRoutine.Mechanism(
                (Measure<Voltage> volts) -> modules[0].runSteerCharacterization(volts.in(Volts)),
                null,
                this));
    driveRoutine =
        new SysIdRoutine(
            new SysIdRoutine.Config(
                null, // Default ramp rate is acceptable
                Volts.of(4), // Reduce dynamic voltage to 4 to prevent motor brownout
                Seconds.of(5),
                // Log state with Phoenix SignalLogger class
                (state) -> SignalLogger.writeString("state", state.toString())),
            new SysIdRoutine.Mechanism(
                (Measure<Voltage> volts) -> runDriveCharacterizationVolts(volts.in(Volts)),
                null,
                this));
  }

  /**
   * Constructs an array of swerve module ios corresponding to the real robot.
   *
   * @return The array of swerve module ios.
   */
  public static ModuleIO[] createTalonFXModules() {
    return new ModuleIO[] {
      new ModuleIOReal(frontLeft),
      new ModuleIOReal(frontRight),
      new ModuleIOReal(backLeft),
      new ModuleIOReal(backRight)
    };
  }

  /**
   * Constructs an array of swerve module ios corresponding to a simulated robot.
   *
   * @return The array of swerve module ios.
   */
  public static ModuleIO[] createSimModules() {
    return new ModuleIO[] {
      new ModuleIOSim("FrontLeft"),
      new ModuleIOSim("FrontRight"),
      new ModuleIOSim("BackLeft"),
      new ModuleIOSim("BackRight")
    };
  }

  public void periodic() {
    odometryLock.lock(); // Prevents odometry updates while reading data
    gyroIO.updateInputs(gyroInputs);
    for (var module : modules) {
      module.updateInputs();
    }
    odometryLock.unlock();
    Logger.processInputs("Swerve/Gyro", gyroInputs);
    for (var module : modules) {
      module.periodic();
    }

    // Stop moving when disabled
    if (DriverStation.isDisabled()) {
      for (var module : modules) {
        module.stop();
      }
    }
    // Log empty setpoint states when disabled
    if (DriverStation.isDisabled()) {
      Logger.recordOutput("SwerveStates/Setpoints", new SwerveModuleState[] {});
      Logger.recordOutput("SwerveStates/SetpointsOptimized", new SwerveModuleState[] {});
    }

    Logger.recordOutput("ShotData/Angle", AutoAimStates.curShotData.getRotation());
    Logger.recordOutput("ShotData/Left RPM", AutoAimStates.curShotData.getLeftRPS());
    Logger.recordOutput("ShotData/Right RPM", AutoAimStates.curShotData.getRightRPS());
    Logger.recordOutput("ShotData/Flight Time", AutoAimStates.curShotData.getFlightTimeSeconds());
    // Update odometry
    int deltaCount =
        Math.min(
            gyroInputs.connected ? gyroInputs.odometryYawPositions.length : Integer.MAX_VALUE,
            Arrays.stream(modules)
                .map((m) -> m.getPositionDeltas().length)
                .min(Integer::compare)
                .get());
    for (int deltaIndex = 0; deltaIndex < deltaCount; deltaIndex++) {
      // Read wheel deltas from each module
      SwerveModulePosition[] wheelDeltas = new SwerveModulePosition[4];
      for (int moduleIndex = 0; moduleIndex < 4; moduleIndex++) {
        wheelDeltas[moduleIndex] = modules[moduleIndex].getPositionDeltas()[deltaIndex];
      }

      // The twist represents the motion of the robot since the last
      // sample in x, y, and theta based only on the modules, without
      // the gyro. The gyro is always disconnected in simulation.
      var twist = kinematics.toTwist2d(wheelDeltas);
      if (gyroInputs.connected) {
        // If the gyro is connected, replace the theta component of the twist
        // with the change in angle since the last sample.
        Rotation2d gyroRotation = gyroInputs.odometryYawPositions[deltaIndex];
        twist = new Twist2d(twist.dx, twist.dy, gyroRotation.minus(lastGyroRotation).getRadians());
        lastGyroRotation = gyroRotation;
      }
      // Apply the twist (change since last sample) to the current pose
      pose = pose.exp(twist);
    }
  }

  private void runVelocity(ChassisSpeeds speeds) {
    // Calculate module setpoints
    ChassisSpeeds discreteSpeeds = ChassisSpeeds.discretize(speeds, 0.02);
    SwerveModuleState[] setpointStates = kinematics.toSwerveModuleStates(discreteSpeeds);
    SwerveDriveKinematics.desaturateWheelSpeeds(setpointStates, MAX_LINEAR_SPEED);

    Logger.recordOutput("Swerve/Target Speeds", discreteSpeeds);
    Logger.recordOutput("Swerve/Speed Error", discreteSpeeds.minus(getVelocity()));
    Logger.recordOutput(
        "Swerve/Target Chassis Speeds Field Relative",
        ChassisSpeeds.fromRobotRelativeSpeeds(discreteSpeeds, getRotation()));

    // Send setpoints to modules
    SwerveModuleState[] optimizedSetpointStates =
        Streams.zip(
                Arrays.stream(modules), Arrays.stream(setpointStates), (m, s) -> m.runSetpoint(s))
            .toArray(SwerveModuleState[]::new);

    // Log setpoint states
    Logger.recordOutput("SwerveStates/Setpoints", setpointStates);
    Logger.recordOutput("SwerveStates/SetpointsOptimized", optimizedSetpointStates);
  }

  /**
   * Runs the drive at the desired velocity.
   *
   * @param speeds Speeds in meters/sec
   */
  public Command runVelocityCmd(Supplier<ChassisSpeeds> speeds) {
    return this.run(() -> runVelocity(speeds.get()));
  }

  /** Stops the drive. */
  public Command stopCmd() {
    return runVelocityCmd(ChassisSpeeds::new);
  }

  public Command runVelocityFieldRelative(Supplier<ChassisSpeeds> speeds) {
    return this.runVelocityCmd(
        () -> ChassisSpeeds.fromFieldRelativeSpeeds(speeds.get(), getRotation()));
  }

  /**
   * Stops the drive and turns the modules to an X arrangement to resist movement. The modules will
   * return to their normal orientations the next time a nonzero velocity is requested.
   */
  public Command stopWithXCmd() {
    return this.run(
        () -> {
          Rotation2d[] headings = new Rotation2d[4];
          for (int i = 0; i < modules.length; i++) {
            headings[i] = getModuleTranslations()[i].getAngle();
          }
          kinematics.resetHeadings(headings);
          stopCmd();
        });
  }

  /** Runs forwards at the commanded voltage. */
  private void runDriveCharacterizationVolts(double volts) {
    Arrays.stream(modules).forEach((mod) -> mod.runDriveCharacterization(volts));
  }

  /** Returns the average drive velocity in radians/sec. */
  public double getCharacterizationVelocity() {
    double driveVelocityAverage = 0.0;
    for (var module : modules) {
      driveVelocityAverage += module.getCharacterizationVelocity();
    }
    return driveVelocityAverage / 4.0;
  }

  private SwerveModulePosition[] getModulePositions() {

    SwerveModulePosition[] positions = new SwerveModulePosition[4];
    for (int i = 0; i < 4; i++) {
      positions[i] = modules[i].getPosition();
    }

    return positions;
  }

  /** Returns the module states (turn angles and drive velocitoes) for all of the modules. */
  @AutoLogOutput(key = "SwerveStates/Measured")
  private SwerveModuleState[] getModuleStates() {

    SwerveModuleState[] states = new SwerveModuleState[4];
    for (int i = 0; i < 4; i++) {
      states[i] = modules[i].getState();
    }

    return states;
  }

  @AutoLogOutput(key = "Odometry/Velocity")
  public ChassisSpeeds getVelocity() {
    return ChassisSpeeds.fromRobotRelativeSpeeds(
        kinematics.toChassisSpeeds(
            Arrays.stream(modules).map((m) -> m.getState()).toArray(SwerveModuleState[]::new)),
        getRotation());
  }

  @AutoLogOutput(key = "Odometry/RobotRelativeVelocity")
  public ChassisSpeeds getRobotRelativeSpeeds() {
    return kinematics.toChassisSpeeds(
        (SwerveModuleState[])
            Arrays.stream(modules).map((m) -> m.getState()).toArray(SwerveModuleState[]::new));
  }

  /** Returns the current odometry pose. */
  @AutoLogOutput(key = "Odometry/Robot")
  public Pose2d getPose() {
    return pose;
  }

  /** Returns the current odometry rotation. */
  public Rotation2d getRotation() {
    return pose.getRotation();
  }

  /** Resets the current odometry pose. */
  public void setPose(Pose2d pose) {
    this.pose = pose;
    odometry.resetPosition(gyroInputs.yawPosition, getModulePositions(), pose);
  }

  public void setYaw(Rotation2d yaw) {
    gyroIO.setYaw(yaw);
    setPose(new Pose2d(getPose().getTranslation(), yaw));
  }

  /** Returns an array of module translations. */
  public static Translation2d[] getModuleTranslations() {
    return new Translation2d[] {
      new Translation2d(TRACK_WIDTH_X / 2.0, TRACK_WIDTH_Y / 2.0),
      new Translation2d(TRACK_WIDTH_X / 2.0, -TRACK_WIDTH_Y / 2.0),
      new Translation2d(-TRACK_WIDTH_X / 2.0, TRACK_WIDTH_Y / 2.0),
      new Translation2d(-TRACK_WIDTH_X / 2.0, -TRACK_WIDTH_Y / 2.0)
    };
  }

  public Rotation2d getFutureRotationToTranslation(Pose2d translation, Pose2d pose) {
    double angle = Math.atan2(translation.getY() - pose.getY(), translation.getX() - pose.getX());
    return Rotation2d.fromRadians(angle);
  }

  public Rotation2d getRotationToTranslation(
      Pose2d translation, ChassisSpeeds speedsFieldRelative) {
    double angle =
        Math.atan2(
            translation.getY()
                - getLinearFuturePose(AutoAim.LOOKAHEAD_TIME_SECONDS, speedsFieldRelative).getY(),
            translation.getX()
                - getLinearFuturePose(AutoAim.LOOKAHEAD_TIME_SECONDS, speedsFieldRelative).getX());
    return Rotation2d.fromRadians(angle);
  }

  /**
   * Transforms the speaker pose by the robots current velocity (assumes constant velocity)
   *
   * @return The transformed pose
   */
  public Pose2d getVirtualTarget(ChassisSpeeds speedsRobotRelative) {

    Pose2d target = FieldConstants.getSpeaker();

    double distance =
        getLinearFuturePose(AutoAim.LOOKAHEAD_TIME_SECONDS, speedsRobotRelative)
            .minus(target)
            .getTranslation()
            .getNorm();

    return target.transformBy(
        new Transform2d(
                speedsRobotRelative.vxMetersPerSecond
                    * AutoAim.shotMap.get(distance).getFlightTimeSeconds(),
                speedsRobotRelative.vyMetersPerSecond
                    * AutoAim.shotMap.get(distance).getFlightTimeSeconds(),
                target.getRotation())
            .inverse());
  }

  @AutoLogOutput(key = "AutoAim/Virtual Target")
  public Pose2d getVirtualTarget() {
    return getVirtualTarget(getVelocity());
  }

  public ChoreoTrajectoryState getAutoState(double timestamp) {
    return Choreo.getTrajectory(AutoAimStates.pathName).sample(timestamp);
  }

  /**
   * Gets the pose at some time in the future, assuming constant velocity
   *
   * @param robotRelativeSpeeds the robot relative speed to calculate from
   * @param time time in seconds
   * @return The future pose
   */
  public Pose2d getLinearFuturePose(double time, ChassisSpeeds speedsFieldRelative) {

    ChassisSpeeds speedsRobotRelative =
        ChassisSpeeds.fromFieldRelativeSpeeds(speedsFieldRelative, getRotation());
    return getPose()
        .transformBy(
            new Transform2d(
                speedsRobotRelative.vxMetersPerSecond * time,
                speedsRobotRelative.vyMetersPerSecond * time,
                Rotation2d.fromRadians(speedsRobotRelative.omegaRadiansPerSecond * time)));
  }

  /**
   * Gets the pose at some time in the future, assuming constant velocity and uses robot's current
   * speeed
   *
   * @param time time in seconds
   * @return The future pose
   */
  public Pose2d getLinearFuturePose(double time) {
    return getLinearFuturePose(time, getVelocity());
  }

  /**
   * Gets the pose at some time in the future, assuming constant velocity Uses fixed lookahead time
   * specified in AutoAim.java
   *
   * @return The future pose
   */
  @AutoLogOutput(key = "AutoAim/FuturePose")
  public Pose2d getLinearFuturePose() {
    return getLinearFuturePose(AutoAim.LOOKAHEAD_TIME_SECONDS);
  }

  /**
   * Faces the robot towards a translation on the field Keeps the robot in a linear drive motion for
   * time seconds while rotating
   *
   * @param xMetersPerSecond Requested X velocity
   * @param yMetersPerSecond Requested Y velocity
   * @param time Time in the future to point from
   * @return A command reference that rotates the robot to a computed rotation
   */
  public Command teleopPointTowardsTranslationCmd(
      DoubleSupplier xMetersPerSecond, DoubleSupplier yMetersPerSecond, double time) {
    ProfiledPIDController headingController =
        // assume we can accelerate to max in 2/3 of a second
        new ProfiledPIDController(
            40.0, 0.0, 0.0, new Constraints(MAX_ANGULAR_SPEED, MAX_ANGULAR_SPEED / 0.666666));
    headingController.enableContinuousInput(-Math.PI, Math.PI);

    return Commands.sequence(
        Commands.runOnce(
            () -> {
              AutoAimStates.inputSpeeds =
                  new ChassisSpeeds(
                      xMetersPerSecond.getAsDouble(),
                      yMetersPerSecond.getAsDouble(),
                      getVelocity().omegaRadiansPerSecond);
              AutoAimStates.virtualTarget =
                  getVirtualTarget(
                      ChassisSpeeds.fromFieldRelativeSpeeds(
                          AutoAimStates.inputSpeeds, getRotation()));
              AutoAimStates.rotationsToTranslation =
                  getRotationToTranslation(AutoAimStates.virtualTarget, AutoAimStates.inputSpeeds)
                      .minus(Rotation2d.fromDegrees(180));
            },
            this),
        this.runVelocityFieldRelative(
                () -> {
                  double feedbackOutput =
                      headingController.calculate(
                          getPose().getRotation().getRadians(),
                          AutoAimStates.rotationsToTranslation.getRadians());
                  Logger.recordOutput("AutoAim/Ending Pose", AutoAimStates.endingPose);
                  Logger.recordOutput(
                      "AutoAim/Setpoint Rotation", headingController.getSetpoint().position);
                  Logger.recordOutput(
                      "AutoAim/Setpoint Velocity", headingController.getSetpoint().velocity);
                  Logger.recordOutput(
                      "AutoAim/Goal Rotation", headingController.getGoal().position);
                  Logger.recordOutput(
                      "AutoAim/Goal Velocity", headingController.getGoal().velocity);
                  return new ChassisSpeeds(
                      xMetersPerSecond.getAsDouble(),
                      yMetersPerSecond.getAsDouble(),
                      feedbackOutput + headingController.getSetpoint().velocity);
                })
            .beforeStarting(
                () -> {
                  AutoAimStates.endingPose =
                      new Pose2d(
                          getLinearFuturePose(
                                  AutoAim.LOOKAHEAD_TIME_SECONDS, AutoAimStates.inputSpeeds)
                              .getX(),
                          getLinearFuturePose(
                                  AutoAim.LOOKAHEAD_TIME_SECONDS, AutoAimStates.inputSpeeds)
                              .getY(),
                          AutoAimStates.rotationsToTranslation);
                  Logger.recordOutput("AutoAim/Ending Pose", AutoAimStates.endingPose);
                  headingController.reset(new State(getPose().getRotation().getRadians(), 0));
                  Logger.recordOutput("AutoAim/Translated Target", AutoAimStates.virtualTarget);
                }));
  }

  /**
   * Faces the robot towards a translation on the field Keeps the robot in a linear drive motion for
   * time seconds while rotating
   *
   * @param xMetersPerSecond Requested X velocity
   * @param yMetersPerSecond Requested Y velocity
   * @param time Time in the future to point from
   * @return A command refrence that rotates the robot to a computed rotation
   */
  public Command autonomousPointTowardsTranslationCmd() {
    ProfiledPIDController headingController =
        // assume we can accelerate to max in 2/3 of a second
        new ProfiledPIDController(
            40.0, 0.0, 0.0, new Constraints(MAX_ANGULAR_SPEED, MAX_ANGULAR_SPEED / 0.666666));
    headingController.enableContinuousInput(-Math.PI, Math.PI);

    return Commands.sequence(
        Commands.runOnce(
            () -> {
              AutoAimStates.startingAutonomousSeconds = Timer.getFPGATimestamp();

              AutoAimStates.curState = getAutoState(AutoAim.LOOKAHEAD_TIME_SECONDS);
              AutoAimStates.inputSpeeds =
                  new ChassisSpeeds(
                      AutoAimStates.curState.velocityX,
                      AutoAimStates.curState.velocityY,
                      getVelocity().omegaRadiansPerSecond);
              AutoAimStates.virtualTarget =
                  getVirtualTarget(
                      ChassisSpeeds.fromFieldRelativeSpeeds(
                          AutoAimStates.inputSpeeds, getRotation()));

              AutoAimStates.rotationsToTranslation =
                  getFutureRotationToTranslation(
                      AutoAimStates.virtualTarget, AutoAimStates.curState.getPose());
            },
            this),
        this.runVelocityFieldRelative(
                () -> {
                  double feedbackOutput =
                      headingController.calculate(
                          getPose().getRotation().getRadians(),
                          AutoAimStates.rotationsToTranslation.getRadians());

                  AutoAimStates.curState = getAutoState(AutoAimStates.elapsedAutonomousSeconds);
                  AutoAimStates.elapsedAutonomousSeconds +=
                      Timer.getFPGATimestamp()
                          - AutoAimStates.elapsedAutonomousSeconds
                          - AutoAimStates.startingAutonomousSeconds;
                  System.out.println(AutoAimStates.elapsedAutonomousSeconds);
                  Logger.recordOutput("AutoAim/Ending Pose", AutoAimStates.endingPose);
                  Logger.recordOutput("AutoAim/Virtual Target", AutoAimStates.virtualTarget);
                  Logger.recordOutput(
                      "AutoAim/Setpoint Rotation", headingController.getSetpoint().position);
                  Logger.recordOutput(
                      "AutoAim/Setpoint Velocity", headingController.getSetpoint().velocity);
                  Logger.recordOutput(
                      "AutoAim/Goal Rotation", headingController.getGoal().position);
                  Logger.recordOutput(
                      "AutoAim/Goal Velocity", headingController.getGoal().velocity);
                  return new ChassisSpeeds(
                      AutoAimStates.curState.velocityX,
                      AutoAimStates.curState.velocityY,
                      feedbackOutput + headingController.getSetpoint().velocity);
                })
            .beforeStarting(
                () -> {
                  AutoAimStates.endingPose =
                      new Pose2d(
                          AutoAimStates.curState.x,
                          AutoAimStates.curState.y,
                          AutoAimStates.rotationsToTranslation);
                  Logger.recordOutput("AutoAim/Ending Pose", AutoAimStates.endingPose);
                  headingController.reset(new State(getPose().getRotation().getRadians(), 0));
                  Logger.recordOutput("AutoAim/Translated Target", AutoAimStates.virtualTarget);
                }));
  }

  /**
   * Faces the robot towards a translation on the field Uses a constant lookahead time specified in
   * AutoAim.java
   *
   * @param xMetersPerSecond Requested X velocity
   * @param yMetersPerSecond Requested Y velocity
   * @return A command refrence that rotates the robot to a computed rotation
   */
  public Command teleopPointTowardsTranslationCmd(
      DoubleSupplier xMetersPerSecond, DoubleSupplier yMetersPerSecond) {
    return teleopPointTowardsTranslationCmd(
        xMetersPerSecond, yMetersPerSecond, AutoAim.LOOKAHEAD_TIME_SECONDS);
  }

  public Command runModuleSteerCharacterizationCmd() {
    return Commands.sequence(
        this.runOnce(() -> SignalLogger.start()),
        moduleSteerRoutine.quasistatic(Direction.kForward),
        this.stopCmd().withTimeout(1.0),
        moduleSteerRoutine.quasistatic(Direction.kReverse),
        this.stopCmd().withTimeout(1.0),
        moduleSteerRoutine.dynamic(Direction.kForward),
        this.stopCmd().withTimeout(1.0),
        moduleSteerRoutine.dynamic(Direction.kReverse),
        this.runOnce(() -> SignalLogger.stop()));
  }

  public Command runDriveCharacterizationCmd() {
    return Commands.sequence(
        this.runOnce(() -> SignalLogger.start()),
        driveRoutine.quasistatic(Direction.kForward),
        this.stopCmd().withTimeout(1.0),
        driveRoutine.quasistatic(Direction.kReverse),
        this.stopCmd().withTimeout(1.0),
        driveRoutine.dynamic(Direction.kForward),
        this.stopCmd().withTimeout(1.0),
        driveRoutine.dynamic(Direction.kReverse),
        this.runOnce(() -> SignalLogger.stop()));
  }
}
