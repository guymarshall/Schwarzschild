package ianmarshall;

import cern.colt.matrix.DoubleMatrix2D;

import ianmarshall.MetricComponents.MetricComponent;
import static ianmarshall.MetricComponents.MetricComponent.A;
import static ianmarshall.Worker.DerivativeLevel.None;

import java.util.List;
import java.util.Map.Entry;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In order to apply the simulated annealing method to a specific problem, one must specify the following parameters:
 *   -  the state space:                     s
 *   -  the energy (goal) function:          E(s)
 *   -  the candidate generator procedure:   neighbour(s)
 *   -  the acceptance probability function: P(E, Enew, T)
 *   -  the annealing schedule:              temperature(iteration №).
 */
public class SimulatedAnnealing
{
	private static final Logger logger = LoggerFactory.getLogger(SimulatedAnnealing.class);
	private static final Random m_Random = new Random();    // Remove "static" for multi-instance use

	private double m_dblNeighbourPeakScalingFactor = 0.0;
	private double m_dblAcceptanceProbabilityScalingFactor = 0.0;
	private double m_dblTemperatureScalingFactor = 0.0;
	private double m_dblTemperatureDivisor = 0.0;
//private String m_sLogMessage = null;    // Refactor this for multi-instance use

	public SimulatedAnnealing(StartParameters spStartParameters)
	{
		m_dblNeighbourPeakScalingFactor = spStartParameters.getNeighbourPeakScalingFactor();
		m_dblAcceptanceProbabilityScalingFactor = spStartParameters.getAcceptanceProbabilityScalingFactor();
		m_dblTemperatureScalingFactor = spStartParameters.getTemperatureScalingFactor();
		m_dblTemperatureDivisor = spStartParameters.getTemperatureDivisor();
	}

	/**
	 * The energy (goal) function.
	 * <br/>
	 * Calculate the energy of the state space, which is represented by the supplied tensor values and their derivatives.
	 * @param liG
	 *   The tensor values, with metric components for each value of radius.
	 * @param liGFirstDerivative
	 *   The 1st derivative of the tensor values.
	 * @param liGSecondDerivative
	 *   The 2nd derivative of the tensor values.
	 * @param nRun
	 *   The number of runs already executed. A value of <code>0</code> means no run has yet been executed.
	 * @return
	 *   The energy of the state space.
	 */
	public double energy(List<MetricComponents> liG, List<MetricComponents> liGFirstDerivative,
	 List<MetricComponents> liGSecondDerivative, int nRun)
	{
		double dblSumOfSquaresOfRicciTensorsOverAllR = 0.0;
		int nSize = liG.size();

		for (int i = 0; i < nSize; i++)
		{
			// 3 rows by 1 column
			DoubleMatrix2D dmRicci = Worker.calculateRicciTensorValues(liG, liGFirstDerivative, liGSecondDerivative, i);

	 // boolean bLog = (nRun <= 3) && ((i == 0) || (i == 3));
			boolean bLog = false;

			if (bLog)
			{
				String sMsg = String.format("%n  nRun = %d, i = %d: dmRicci has elements:%n%s .%n",
				 nRun, i, dmRicci.toString());
				logger.info(sMsg);
			}

			double dblSumOfSquaresOfRicciTensors = 0.0;

			for (int j = 0; j < dmRicci.rows(); j++)
			{
				double dblRicciTensor = dmRicci.get(j, 0);
				dblSumOfSquaresOfRicciTensors += dblRicciTensor * dblRicciTensor;
			}

			final int nStart;
			final int nFinish;
			if (i == 0)
			{
				nStart = i;        // Forward difference for the first point
				nFinish = nStart + 1;
			}
			else if (i < nSize - 1)
			{
				nStart = i - 1;    // Central difference for an internal point
				nFinish = nStart + 2;
			}
			else
			{
				nStart = i - 1;    // Backward difference for the last point
				nFinish = nStart + 1;
			}

			Entry<Double, Double> entry = Worker.getMetricComponentOfDerivativeLevel(liG, null, null, None, nStart, A);
			double dblRStart = entry.getKey();
			entry = Worker.getMetricComponentOfDerivativeLevel(liG, null, null, None, nFinish, A);
			double dblRFinish = entry.getKey();

			dblSumOfSquaresOfRicciTensorsOverAllR +=
			 dblSumOfSquaresOfRicciTensors * (dblRFinish - dblRStart) / (nFinish - nStart);
		}

		return dblSumOfSquaresOfRicciTensorsOverAllR;
	}

	/**
	 * The candidate generator procedure.
	 * @param liG
	 *   The tensor values, with metric components for each value of radius.
	 * @return
	 *   The tensor values of the candidate, with metric components for each value of radius.
	 */
	public List<MetricComponents> neighbour(List<MetricComponents> liG)
	{
		List<MetricComponents> liGResult = MetricComponents.deepCopyMetricComponents(liG);

		int nSize = liGResult.size();
 // double dblStandardDeviationMax = nSize / 4.0;
		int nIndexCentre = m_Random.nextInt(nSize);

 // double dblStandardDeviation = Math.floor(Math.random() * dblStandardDeviationMax);
 // dblStandardDeviation = Math.max(0.1, dblStandardDeviation);
		double dblStandardDeviation = 1.0;

		// Equally likely between -m_dblNeighbourPeakScalingFactor and +m_dblNeighbourPeakScalingFactor inclusive
		double dblDeltaPeak = m_dblNeighbourPeakScalingFactor * ((2.0 * Math.random()) - 1.0);

		MetricComponent[] amcMetricComponents = MetricComponent.values();
		int nMCIndex = m_Random.nextInt(amcMetricComponents.length);
		MetricComponent mc = amcMetricComponents[nMCIndex];

 // m_sLogMessage = String.format("SimulatedAnnealing.neighbour(...):"
 //  + "%n  nIndexCentre         = %d,"
 //  + "%n  dblStandardDeviation = %f,"
 //  + "%n  dblDeltaPeak         = %f.",
 //  nIndexCentre, dblStandardDeviation, dblDeltaPeak);

		for (int i = 0; i < liGResult.size(); i++)
		{
			double dblExponent = (i - nIndexCentre) / dblStandardDeviation;
			double dblDelta = dblDeltaPeak * Math.exp(-dblExponent * dblExponent);

			double dblMC = Worker.getMetricComponentOfDerivativeLevel(liGResult, null, null, None, i, mc)
			 .getValue();
			Worker.setMetricComponentOfDerivativeLevel(liGResult, null, null, None, i, mc, dblMC + dblDelta);
		}

		return liGResult;
	}

	/**
	 * The acceptance probability function.
	 * <br/>
	 * Calculate the probability of the jump from the current to the new state.
	 * @param dblEnergyCurrent
	 *   The energy of the current state.
	 * @param dblEnergyNew
	 *   The energy of the new (proposed) state.
	 * @param dblTemperature
	 *   The simulated annealing temperature.
	 * @return
	 *   The probability of the jump from the current to the new state.
	 */
	public double acceptanceProbability(double dblEnergyCurrent, double dblEnergyNew, double dblTemperature)
	{
		double result = 0.0;

		if (dblEnergyNew <= dblEnergyCurrent)
			result = 1.0;
		else if (dblTemperature <= 0.0)
			result = 0.0;
		else    // exp(-k(Enew - E)/T)
			result = Math.exp(-m_dblAcceptanceProbabilityScalingFactor *(dblEnergyNew - dblEnergyCurrent) / dblTemperature);

		return result;
	}

	/**
	 * The annealing schedule.
	 * <br/>
	 * Calculate the simulated annealing temperature.
	 * @param nIteration
	 *   The <code>1</code>-based iteration №.
	 * @param nRuns
	 *   The total № of runs (iterations).
	 * @return
	 * The simulated annealing temperature.
	 */
	public double temperature(int nIteration, int nRuns)
	{
		double result = 0.0;
		double dblFactor = 1.0 - (((double)(nIteration - 1)) / m_dblTemperatureDivisor);

		if (dblFactor > 0.0)
		{
			result = m_dblTemperatureScalingFactor * Math.pow(dblFactor, 4.0);

			if (result < 0.0)
				result = 0.0;
		}

 // if (result <= 4.0)
 // 	result = 4.0;

		return result;
	}

	/*
	 * Obtain the latest log message, then clear it to <code>null</code>.
	 * @return
	 *   The latest log message. If there is none then return <code>null</code>.
	 */
	/*
	public String popLatestLogMessage()
	{
		String sResult = m_sLogMessage;
		m_sLogMessage = null;
		return sResult;
	}
	*/
}
