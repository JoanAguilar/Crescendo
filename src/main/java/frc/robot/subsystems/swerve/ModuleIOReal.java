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

import com.ctre.phoenix6.BaseStatusSignal;
import com.ctre.phoenix6.StatusSignal;
import com.ctre.phoenix6.configs.CANcoderConfiguration;
import com.ctre.phoenix6.configs.TalonFXConfiguration;
import com.ctre.phoenix6.controls.MotionMagicVelocityVoltage;
import com.ctre.phoenix6.controls.MotionMagicVoltage;
import com.ctre.phoenix6.controls.VoltageOut;
import com.ctre.phoenix6.hardware.CANcoder;
import com.ctre.phoenix6.hardware.TalonFX;
import com.ctre.phoenix6.signals.FeedbackSensorSourceValue;
import com.ctre.phoenix6.signals.InvertedValue;
import com.ctre.phoenix6.signals.NeutralModeValue;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.util.Units;
import frc.robot.subsystems.swerve.Module.ModuleConstants;
import java.util.Queue;

/**
 * Module IO implementation for Talon FX drive motor controller, Talon FX turn motor controller, and
 * CANcoder
 *
 * <p>NOTE: This implementation should be used as a starting point and adapted to different hardware
 * configurations (e.g. If using an analog encoder, copy from "ModuleIOSparkMax")
 *
 * <p>To calibrate the absolute encoder offsets, point the modules straight (such that forward
 * motion on the drive motor will propel the robot forward) and copy the reported values from the
 * absolute encoders using AdvantageScope. These values are logged under
 * "/Swerve/ModuleX/TurnAbsolutePositionRad"
 */
public class ModuleIOReal implements ModuleIO {
  // Constants
  private static final boolean IS_TURN_MOTOR_INVERTED = false;

  private final String name;

  // Hardware
  private final TalonFX driveTalon;
  private final TalonFX turnTalon;
  private final CANcoder cancoder;

  // Signals
  private final StatusSignal<Double> drivePosition;
  private final Queue<Double> drivePositionQueue;
  private final StatusSignal<Double> driveVelocity;
  private final StatusSignal<Double> driveAppliedVolts;
  private final StatusSignal<Double> driveCurrent;

  private final StatusSignal<Double> turnAbsolutePosition;
  private final StatusSignal<Double> turnPosition;
  private final Queue<Double> turnPositionQueue;
  private final StatusSignal<Double> turnVelocity;
  private final StatusSignal<Double> turnAppliedVolts;
  private final StatusSignal<Double> turnCurrent;

  // Control modes
  private final VoltageOut driveVoltage = new VoltageOut(0.0).withEnableFOC(true);
  private final VoltageOut turnVoltage = new VoltageOut(0.0).withEnableFOC(true);
  private final MotionMagicVelocityVoltage drivePIDF =
      new MotionMagicVelocityVoltage(0.0).withEnableFOC(true);
  private final MotionMagicVoltage turnPID = new MotionMagicVoltage(0.0).withEnableFOC(true);

  public ModuleIOReal(ModuleConstants constants) {
    name = constants.prefix();

    driveTalon = new TalonFX(constants.driveID());
    turnTalon = new TalonFX(constants.turnID());
    cancoder = new CANcoder(constants.cancoderID());

    var driveConfig = new TalonFXConfiguration();
    // Current limits
    // TODO: Do we want to limit supply current?
    driveConfig.CurrentLimits.StatorCurrentLimit = Module.DRIVE_STATOR_CURRENT_LIMIT;
    driveConfig.CurrentLimits.StatorCurrentLimitEnable = true;
    // Inverts
    driveConfig.MotorOutput.Inverted = InvertedValue.Clockwise_Positive;
    driveConfig.MotorOutput.NeutralMode = NeutralModeValue.Brake;
    // Sensor
    // Meters per second
    driveConfig.Feedback.SensorToMechanismRatio =
        (Module.DRIVE_GEAR_RATIO) * (1.0 / (Module.WHEEL_RADIUS * 2 * Math.PI));
    // Controls Gains
    driveConfig.Slot0.kV = 2.5;
    // (5800.0 * (Module.DRIVE_GEAR_RATIO) * (1.0 / (Module.WHEEL_RADIUS * 2 * Math.PI)))
    //     / 12.0; // Hypothetical based on free speed
    driveConfig.Slot0.kA = 0.0; // TODO: Find using sysid or hand tuning
    driveConfig.Slot0.kS = 0.0;
    driveConfig.Slot0.kP = 0.25; // Guess
    driveConfig.Slot0.kD = 0.0;

    driveTalon.getConfigurator().apply(driveConfig);

    var turnConfig = new TalonFXConfiguration();
    // Current limits
    turnConfig.CurrentLimits.StatorCurrentLimit = Module.TURN_STATOR_CURRENT_LIMIT;
    turnConfig.CurrentLimits.StatorCurrentLimitEnable = true;
    // Inverts
    turnConfig.MotorOutput.Inverted =
        IS_TURN_MOTOR_INVERTED
            ? InvertedValue.Clockwise_Positive
            : InvertedValue.CounterClockwise_Positive;
    turnConfig.MotorOutput.NeutralMode = NeutralModeValue.Brake;
    // Fused Cancoder
    turnConfig.Feedback.FeedbackSensorSource = FeedbackSensorSourceValue.FusedCANcoder;
    turnConfig.Feedback.FeedbackRemoteSensorID = constants.cancoderID();
    turnConfig.Feedback.RotorToSensorRatio = Module.TURN_GEAR_RATIO;
    turnConfig.Feedback.SensorToMechanismRatio = 1.0;
    turnConfig.Feedback.FeedbackRotorOffset =
        0.0; // Is this correct? Cancoder config should handle it
    // Controls Gains
    turnConfig.Slot0.kV = 0.0;
    // (5800.0 / Module.TURN_GEAR_RATIO)
    //     / 12.0; // Free speed over voltage, should find empirically
    turnConfig.Slot0.kA = 0.0;
    // Module.TURN_GEAR_RATIO
    //     * (9.37 / 483.0)
    //     / (0.004 * (12.0 / 483.0)); // Based on motor dynamics math, should find in real life
    // gearing * Kt (torque per amp) / (intertia * resistance (nominal voltage / stall current))
    // Yeah its messy and should be found using sysid later but its worth trying as a first guess
    // If this works we can use a similar technique on future mechanisms
    turnConfig.Slot0.kS = 0.0; // TODO: Find empirically
    turnConfig.Slot0.kP = 50.0;
    turnConfig.Slot0.kD = 0.0;
    turnConfig.ClosedLoopGeneral.ContinuousWrap = true;

    turnTalon.getConfigurator().apply(turnConfig);

    var cancoderConfig = new CANcoderConfiguration();
    cancoderConfig.MagnetSensor.MagnetOffset = constants.cancoderOffset().getRotations();
    cancoder.getConfigurator().apply(cancoderConfig);

    drivePosition = driveTalon.getPosition();
    drivePositionQueue =
        PhoenixOdometryThread.getInstance().registerSignal(driveTalon, driveTalon.getPosition());
    driveVelocity = driveTalon.getVelocity();
    driveAppliedVolts = driveTalon.getMotorVoltage();
    driveCurrent = driveTalon.getStatorCurrent();

    turnAbsolutePosition = cancoder.getAbsolutePosition();
    turnPosition = turnTalon.getPosition();
    turnPositionQueue =
        PhoenixOdometryThread.getInstance().registerSignal(turnTalon, turnTalon.getPosition());
    turnVelocity = turnTalon.getVelocity();
    turnAppliedVolts = turnTalon.getMotorVoltage();
    turnCurrent = turnTalon.getStatorCurrent();

    BaseStatusSignal.setUpdateFrequencyForAll(
        Module.ODOMETRY_FREQUENCY_HZ, drivePosition, turnPosition);
    BaseStatusSignal.setUpdateFrequencyForAll(
        50.0,
        driveVelocity,
        driveAppliedVolts,
        driveCurrent,
        turnAbsolutePosition,
        turnVelocity,
        turnAppliedVolts,
        turnCurrent);
    driveTalon.optimizeBusUtilization();
    turnTalon.optimizeBusUtilization();
    cancoder.optimizeBusUtilization();
  }

  @Override
  public void updateInputs(ModuleIOInputs inputs) {
    BaseStatusSignal.refreshAll(
        drivePosition,
        driveVelocity,
        driveAppliedVolts,
        driveCurrent,
        turnAbsolutePosition,
        turnPosition,
        turnVelocity,
        turnAppliedVolts,
        turnCurrent);

    inputs.drivePositionMeters = drivePosition.getValueAsDouble();
    inputs.driveVelocityMetersPerSec = driveVelocity.getValueAsDouble();
    inputs.driveAppliedVolts = driveAppliedVolts.getValueAsDouble();
    inputs.driveCurrentAmps = new double[] {driveCurrent.getValueAsDouble()};

    inputs.turnAbsolutePosition = Rotation2d.fromRotations(turnAbsolutePosition.getValueAsDouble());
    inputs.turnPosition = Rotation2d.fromRotations(turnPosition.getValueAsDouble());
    inputs.turnVelocityRadPerSec = Units.rotationsToRadians(turnVelocity.getValueAsDouble());
    inputs.turnAppliedVolts = turnAppliedVolts.getValueAsDouble();
    inputs.turnCurrentAmps = new double[] {turnCurrent.getValueAsDouble()};

    inputs.odometryDrivePositionsMeters =
        drivePositionQueue.stream().mapToDouble(Units::rotationsToRadians).toArray();
    inputs.odometryTurnPositions =
        turnPositionQueue.stream()
            .map(Rotation2d::fromRotations) // should be after offset + gear ratio
            .toArray(Rotation2d[]::new);
    drivePositionQueue.clear();
    turnPositionQueue.clear();
  }

  @Override
  public void setDriveVoltage(final double volts) {
    driveTalon.setControl(driveVoltage.withOutput(volts));
  }

  @Override
  public void setTurnVoltage(final double volts) {
    turnTalon.setControl(turnVoltage.withOutput(volts));
  }

  @Override
  public void setDriveSetpoint(final double metersPerSecond) {
    driveTalon.setControl(drivePIDF.withVelocity(metersPerSecond));
  }

  @Override
  public void setTurnSetpoint(final Rotation2d rotation) {
    turnTalon.setControl(turnPID.withPosition(rotation.getRotations()));
  }

  @Override
  public String getModuleName() {
    return name;
  }
}
