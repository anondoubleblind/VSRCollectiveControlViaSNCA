package it.units.erallab.hmsrobots.core.controllers.snndiscr;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import it.units.erallab.hmsrobots.core.controllers.AbstractController;
import it.units.erallab.hmsrobots.core.controllers.DistributedSensing;
import it.units.erallab.hmsrobots.core.controllers.snndiscr.converters.stv.QuantizedSpikeTrainToValueConverter;
import it.units.erallab.hmsrobots.core.controllers.snndiscr.converters.vts.QuantizedValueToSpikeTrainConverter;
import it.units.erallab.hmsrobots.core.objects.SensingVoxel;
import it.units.erallab.hmsrobots.util.Grid;
import it.units.erallab.hmsrobots.util.SerializationUtils;
import org.apache.commons.lang3.ArrayUtils;

import java.util.Arrays;
import java.util.stream.IntStream;

public class QuantizedDistributedSpikingSensing extends AbstractController<SensingVoxel> {

  protected static final int ARRAY_SIZE = QuantizedValueToSpikeTrainConverter.ARRAY_SIZE;

  protected enum Dir {

    N(0, -1, 0),
    E(1, 0, 1),
    S(0, 1, 2),
    W(-1, 0, 3);

    final int dx;
    final int dy;
    private final int index;

    Dir(int dx, int dy, int index) {
      this.dx = dx;
      this.dy = dy;
      this.index = index;
    }

    private static Dir adjacent(Dir dir) {
      return switch (dir) {
        case N -> Dir.S;
        case E -> Dir.W;
        case S -> Dir.N;
        case W -> Dir.E;
      };
    }
  }

  @JsonProperty
  protected final int signals;
  @JsonProperty
  private final Grid<Integer> nOfInputGrid;
  @JsonProperty
  private final Grid<Integer> nOfOutputGrid;
  @JsonProperty
  private final Grid<QuantizedMultivariateSpikingFunction> functions;
  @JsonProperty
  private final Grid<QuantizedSpikeTrainToValueConverter> outputConverters;
  @JsonProperty
  private final Grid<QuantizedValueToSpikeTrainConverter[]> inputConverters;

  private double previousTime = 0;
  protected final Grid<int[][]> lastSignalsGrid;
  private final Grid<int[][]> currentSignalsGrid;

  @JsonCreator
  public QuantizedDistributedSpikingSensing(
      @JsonProperty("signals") int signals,
      @JsonProperty("nOfInputGrid") Grid<Integer> nOfInputGrid,
      @JsonProperty("nOfOutputGrid") Grid<Integer> nOfOutputGrid,
      @JsonProperty("functions") Grid<QuantizedMultivariateSpikingFunction> functions,
      @JsonProperty("outputConverters") Grid<QuantizedSpikeTrainToValueConverter> outputConverters,
      @JsonProperty("inputConverters") Grid<QuantizedValueToSpikeTrainConverter[]> inputConverters
  ) {
    this.signals = signals;
    this.nOfInputGrid = nOfInputGrid;
    this.nOfOutputGrid = nOfOutputGrid;
    this.functions = functions;
    this.outputConverters = outputConverters;
    this.inputConverters = inputConverters;
    lastSignalsGrid = Grid.create(functions, f -> new int[signals * Dir.values().length][ARRAY_SIZE]);
    currentSignalsGrid = Grid.create(functions, f -> new int[signals * Dir.values().length][ARRAY_SIZE]);
    reset();
  }

  public QuantizedDistributedSpikingSensing(Grid<? extends SensingVoxel> voxels, int signals, QuantizedSpikingFunction spikingFunction, QuantizedValueToSpikeTrainConverter valueToSpikeTrainConverter, QuantizedSpikeTrainToValueConverter spikeTrainToValueConverter) {
    this(
        signals,
        Grid.create(voxels, v -> (v == null) ? 0 : DistributedSensing.nOfInputs(v, signals)),
        Grid.create(voxels, v -> (v == null) ? 0 : DistributedSensing.nOfOutputs(v, signals)),
        Grid.create(
            voxels, v -> (v == null) ? null : new QuantizedMultilayerSpikingNetwork(DistributedSensing.nOfInputs(v, signals),
                new int[]{DistributedSensing.nOfInputs(v, signals), DistributedSensing.nOfInputs(v, signals)},
                DistributedSensing.nOfOutputs(v, signals), (x, y) -> spikingFunction)),
        Grid.create(voxels, v -> (v == null) ? null : SerializationUtils.clone(spikeTrainToValueConverter)),
        Grid.create(voxels, v -> (v == null) ? null : IntStream.range(0, v.getSensors().stream().mapToInt(s -> s.getDomains().length).sum()).mapToObj(i -> SerializationUtils.clone(valueToSpikeTrainConverter)).toArray(QuantizedValueToSpikeTrainConverter[]::new))
    );
  }


  public Grid<QuantizedMultivariateSpikingFunction> getFunctions() {
    return functions;
  }

  public void reset() {
    previousTime = 0;
    for (int x = 0; x < lastSignalsGrid.getW(); x++) {
      for (int y = 0; y < lastSignalsGrid.getH(); y++) {
        lastSignalsGrid.set(x, y, new int[signals * Dir.values().length][ARRAY_SIZE]);
        currentSignalsGrid.set(x, y, new int[signals * Dir.values().length][ARRAY_SIZE]);
        if (outputConverters.get(x, y) != null) {
          outputConverters.get(x, y).reset();
        }
        if (inputConverters.get(x, y) != null) {
          Arrays.stream(inputConverters.get(x, y)).forEach(QuantizedValueToSpikeTrainConverter::reset);
        }
        if (functions.get(x, y) != null) {
          functions.get(x, y).reset();
        }
      }
    }
  }

  @Override
  public Grid<Double> computeControlSignals(double t, Grid<? extends SensingVoxel> voxels) {
    Grid<Double> controlSignals = Grid.create(voxels);
    for (Grid.Entry<? extends SensingVoxel> entry : voxels) {
      if (entry.getValue() == null) {
        continue;
      }
      //get inputs
      int[][] lastSignals = getLastSignals(entry.getX(), entry.getY());
      int[][] sensorValues = convertSensorReadings(entry.getValue().getSensorReadings(), inputConverters.get(entry.getX(), entry.getY()), t);
      int[][] inputs = ArrayUtils.addAll(lastSignals, sensorValues);
      //compute outputs
      QuantizedMultivariateSpikingFunction function = functions.get(entry.getX(), entry.getY());
      int[][] outputs = function != null ? function.apply(t, inputs) : new int[nOfOutputs(entry.getX(), entry.getY())][ARRAY_SIZE];
      //apply outputs
      double force = outputConverters.get(entry.getX(), entry.getY()).convert(outputs[0], t - previousTime);
      controlSignals.set(entry.getX(), entry.getY(), force);
      System.arraycopy(outputs, 1, currentSignalsGrid.get(entry.getX(), entry.getY()), 0, outputs.length - 1);
    }
    previousTime = t;
    for (Grid.Entry<? extends SensingVoxel> entry : voxels) {
      System.arraycopy(currentSignalsGrid.get(entry.getX(), entry.getY()), 0, lastSignalsGrid.get(entry.getX(), entry.getY()), 0, signals * Dir.values().length);
    }
    return controlSignals;
  }

  protected int[][] getLastSignals(int x, int y) {
    int[][] values = new int[signals * Dir.values().length][];
    if (signals <= 0) {
      return values;
    }
    int c = 0;
    for (Dir dir : Dir.values()) {
      int adjacentX = x + dir.dx;
      int adjacentY = y + dir.dy;
      int[][] lastSignals = lastSignalsGrid.get(adjacentX, adjacentY);
      if (lastSignals != null) {
        int index = Dir.adjacent(dir).index;
        System.arraycopy(lastSignals, index * signals, values, c, signals);
      }
      c = c + signals;
    }
    for (int i = 0; i < values.length; i++) {
      if (values[i] == null) {
        values[i] = new int[QuantizedValueToSpikeTrainConverter.ARRAY_SIZE];
      }
    }
    return values;
  }

  private int[][] convertSensorReadings(double[] sensorsReadings, QuantizedValueToSpikeTrainConverter[] valueToSpikeTrainConverters, double t) {
    int[][] convertedValues = new int[sensorsReadings.length][];
    IntStream.range(0, sensorsReadings.length).forEach(i -> convertedValues[i] = valueToSpikeTrainConverters[i].convert(sensorsReadings[i], t - previousTime, t));
    return convertedValues;
  }

  public int nOfInputs(int x, int y) {
    return nOfInputGrid.get(x, y);
  }

  public int nOfOutputs(int x, int y) {
    return nOfOutputGrid.get(x, y);
  }

  @Override
  public String toString() {
    return "DistributedSpikingSensing{" +
        "signals=" + signals +
        ", functions=" + functions +
        '}';
  }

}
