package ianmarshall;

import cern.colt.matrix.DoubleFactory2D;
import cern.colt.matrix.DoubleMatrix2D;

import ianmarshall.MetricComponents.MetricComponent;
import static ianmarshall.MetricComponents.MetricComponent.A;
import static ianmarshall.MetricComponents.MetricComponent.B;
import static ianmarshall.Worker.DerivativeLevel.First;
import static ianmarshall.Worker.DerivativeLevel.None;
import static ianmarshall.Worker.DerivativeLevel.Second;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Worker implements Runnable
{
	public enum DerivativeLevel
	{
		None, First, Second;

		public DerivativeLevel getDiffential()
		{
			DerivativeLevel dlResult = None;

			switch (this)
			{
				case None:
					dlResult = First;
					break;
				case First:
					dlResult = Second;
					break;
				case Second:
				default:
					throw new RuntimeException("Invalid derivative level.");
			}

			return dlResult;
		};

		public DerivativeLevel getIntegral()
		{
			DerivativeLevel dlResult = None;

			switch (this)
			{
				case First:
					dlResult = None;
					break;
				case Second:
					dlResult = First;
					break;
				case None:
				default:
					throw new RuntimeException("Invalid derivative level.");
			}

			return dlResult;
		};
	}

	public class WorkerUncaughtExceptionHandler implements UncaughtExceptionHandler
	{
		public WorkerUncaughtExceptionHandler()
		{
		}

		@Override
		public void uncaughtException(Thread t, Throwable th)
		{
			m_WorkerResult = new WorkerResult(m_bProcessingCompleted, th, m_nRun, m_liG);
			m_bStopped = true;
		}

	}

	// private static final double DBL_SUCCESS_LOG_PROBABILITY = 0.001;
	private static final Logger logger = LoggerFactory.getLogger(Worker.class);
	private static final StringBuilder s_sbMoveLog = new StringBuilder();    // Refactor this for multi-instance use
	private int m_nRun = 0;
	private int m_nRuns = 0;

	// These are tensor values, with metric components for each value of radius
	private List<MetricComponents> m_liG = null;
	private List<MetricComponents> m_liGFirstDerivative = null;
	private List<MetricComponents> m_liGSecondDerivative = null;

	private boolean m_bFirstRun = true;    // This will also be true when resuming running after a pause
	private volatile boolean m_bStopping = false;
	private boolean m_bStopped = false;
	private boolean m_bProcessingCompleted = false;
	private WorkerUncaughtExceptionHandler m_wuehExceptionHandler = null;
	private WorkerResult m_WorkerResult = null;

	private SimulatedAnnealing m_saSimulatedAnnealing = null;
	private double m_dblEnergyCurrent = -1.0;

	/**
	 * The constructor.
	 * @param spStartParameters
	 *   The the application's start parameters.
	 * @param nRun
	 *   The number of runs already executed. A value of <code>0</code> means no run has yet been executed.
	 * @param liG
	 *   If not <code>null</code> then use this to set the metric tensor values, otherwise calculate the initial values.
	 */
	public Worker(StartParameters spStartParameters, int nRun, List<MetricComponents> liG)
	{
		m_nRun = nRun;
		m_nRuns = spStartParameters.getNumberOfRuns();
		m_liG = liG;
		m_wuehExceptionHandler = new WorkerUncaughtExceptionHandler();
		m_saSimulatedAnnealing = new SimulatedAnnealing(spStartParameters);
	}

	public void stopExecution()
	{
		m_bStopping = true;
		logger.info(String.format("Stopping run number %s...", SchwarzschildSimulatedAnnealing.formatInteger(m_nRun)));
	}

	/**
	 * @return
	 *   Whether working has stopped, whether all processing has been completed or not.
	 */
	public boolean getStopped()
	{
		return m_bStopped;
	}

	public WorkerUncaughtExceptionHandler getWorkerUncaughtExceptionHandler()
	{
		return m_wuehExceptionHandler;
	}

	public WorkerResult getWorkerResult()
	{
		return m_WorkerResult;
	}

	@Override
	public void run()
	{
		m_bStopping = false;
		m_bStopped = false;

		while ((!m_bStopping) && (m_nRun < m_nRuns))
		{
			m_nRun++;
	 // logger.info(String.format("Started run number %s.", SchwarzschildSimulatedAnnealing.formatInteger(m_nRun)));

			if (m_bFirstRun)
			{
				if (m_liG == null)
					initialiseMetricTensors();

				if ((m_liGFirstDerivative == null) || (m_liGSecondDerivative == null))
				{
					m_liGFirstDerivative  = MetricComponents.deepCopyMetricComponents(m_liG);
					m_liGSecondDerivative = MetricComponents.deepCopyMetricComponents(m_liG);
				}

				calculateAllDifferentialsForAllValues(m_liG, m_liGFirstDerivative, m_liGSecondDerivative);

				// The current energy has not been calculated yet
				m_dblEnergyCurrent = m_saSimulatedAnnealing.energy(m_liG, m_liGFirstDerivative, m_liGSecondDerivative, m_nRun);

				m_bFirstRun = false;
			}

			List<MetricComponents> liGNew = m_saSimulatedAnnealing.neighbour(m_liG);
			List<MetricComponents> liGNewFirstDerivative = MetricComponents.deepCopyMetricComponents(m_liGFirstDerivative);
			List<MetricComponents> liGNewSecondDerivative = MetricComponents.deepCopyMetricComponents(m_liGSecondDerivative);
			calculateAllDifferentialsForAllValues(liGNew, liGNewFirstDerivative, liGNewSecondDerivative);
			double dblEnergyNew = m_saSimulatedAnnealing.energy(liGNew, liGNewFirstDerivative, liGNewSecondDerivative,
			 m_nRun);
			double dblTemperature = m_saSimulatedAnnealing.temperature(m_nRun, m_nRuns);
			double dblProbability = m_saSimulatedAnnealing.acceptanceProbability(m_dblEnergyCurrent, dblEnergyNew,
			 dblTemperature);
			boolean bAcceptMove = Math.random() < dblProbability;
			String sLogEntry = null;
	 // bAcceptMove = false;    // Delete this line

			if (bAcceptMove)
			{
		 // if (Math.random() < DBL_SUCCESS_LOG_PROBABILITY)
		 // {
					sLogEntry = String.format("Run number %s:"
					 + "    ***  Accepted move from energy %f to %f at temperature %f with probability %.5f.  ***",
					 SchwarzschildSimulatedAnnealing.formatInteger(m_nRun), m_dblEnergyCurrent, dblEnergyNew, dblTemperature,
					 dblProbability);

					int nStartLength = s_sbMoveLog.length();
					s_sbMoveLog.append(String.format("%n  run %d: %s", m_nRun, sLogEntry));

					if (nStartLength == 0)
					{
						String sRemove = String.format("%n");
						int nLengthRemove = sRemove.length();
						s_sbMoveLog.delete(0, nLengthRemove);
					}
		 // }

				m_liG = liGNew;
				m_liGFirstDerivative = liGNewFirstDerivative;
				m_liGSecondDerivative = liGNewSecondDerivative;
				m_dblEnergyCurrent = dblEnergyNew;

		 // String sLogMessage = m_saSimulatedAnnealing.popLatestLogMessage();
		 // logger.info(sLogMessage);
			}
			else if (dblProbability >= 0.5)
			{
				sLogEntry = String.format("Run number %s:"
				 + " rejected move from energy %f to %f with probability %.5f.",
				 SchwarzschildSimulatedAnnealing.formatInteger(m_nRun), m_dblEnergyCurrent, dblEnergyNew, dblProbability);

		 // sLogEntry = String.format("%n***  Remove the setting of bAcceptMove to false.  ***");
			}

			if (sLogEntry == null)
				sLogEntry = String.format("Run number %s: (pre-move) energy = %f.",
				 SchwarzschildSimulatedAnnealing.formatInteger(m_nRun), m_dblEnergyCurrent);

			if (sLogEntry != null)
			{
				logger.info(sLogEntry);
		 // logger.info(String.format("Completed run number %s with current energy %f.",
		 //  SchwarzschildSimulatedAnnealing.formatInteger(m_nRun), m_dblEnergyCurrent));
			}

	 // logger.info(String.format("Completed run number %s with current energy %f.",
	 //  SchwarzschildSimulatedAnnealing.formatInteger(m_nRun), m_dblEnergyCurrent));
		}

		logger.info(String.format("Move log is:%n%s", s_sbMoveLog));
		reportFinalTensorValues();

		if (m_nRun >= m_nRuns)
		{
			s_sbMoveLog.setLength(0);
			m_bProcessingCompleted = true;
			logger.info("All processing has been completed.");
		}
		else
			logger.info("Stopped before all processing completed.");

		m_WorkerResult = new WorkerResult(m_bProcessingCompleted, null, m_nRun, m_liG);
		m_bStopped = true;
	}

	/**
	 * Initialise the metric tensor, and its first and second derivatives with respect to radius,
	 * with start values for logarithmically-graduated radius values.
	 */
	private void initialiseMetricTensors()
	{
		m_liG = new ArrayList<>();
		m_liGFirstDerivative = new ArrayList<>();
		m_liGSecondDerivative = new ArrayList<>();

		StringBuilder sbLog = new StringBuilder("Initialising the metric components (a selection is shown)...");
		String sIndent = " ".repeat(72);

		sbLog.append(String.format(
			 "%n%1$sindex                   R                   A                   B"
		 + "%n%1$s-----  ------------------  ------------------  ------------------",
		 sIndent));

		String sFormat = "%n" + sIndent + "%5d  %,18.12f  %,18.12f  %,18.12f";

		final double DBL_R_MIN = 1.01;
		final double DBL_R_MAX = 100.0;
		final double DBL_STEP_FACTOR_RADIUS = 1.014;
		double dblR = DBL_R_MIN;
		int i = 0;
		boolean bLoop = true;
		boolean bOneMoreLoop = false;

		while (bLoop)
		{
			if (bOneMoreLoop)
				bLoop = false;

	 // double dblA =  1.0 * (1.0 - (1.0 / dblR));
	 // double dblB =  1.0 * (-1.0 / (1.0 - (1.0 / dblR)));
			double dblA =  1.0;
			double dblB =  -1.0;

			m_liG.add(new MetricComponents(dblR, dblA, dblB));
			m_liGFirstDerivative.add(new MetricComponents(dblR, 0.0, 0.0));
			m_liGSecondDerivative.add(new MetricComponents(dblR, 0.0, 0.0));

			if ((i >= 662) || ((i % 100) == 0))
				sbLog.append(String.format(sFormat, i, dblR, dblA, dblB));

			double dblRNew = ((dblR  - 1.0) * DBL_STEP_FACTOR_RADIUS) + 1.0;

			if (dblRNew < DBL_R_MAX)
				dblR = dblRNew;
			else if (dblR < DBL_R_MAX)
			{
				// We shall loop once more only, and then not rely on comparison precision
				dblR = DBL_R_MAX;
				bOneMoreLoop = true;
			}
			else
				bLoop = false;

			i++;
		}

		logger.info(sbLog.toString());
		logger.info("The metric components have been initialised.");
	}

	/**
	 * Calculate the first and second differentials of all the metric tensor components with respect to radius.
	 * <br>
	 * All of the parameters must be not <code>null</code> and contain the same number of elements
	 * for the same radius values. This number of elements must be at least 5.
	 * @param liG
	 *   The metric tensor components.
	 * @param liGFirstDerivative
	 *   The first differential of the metric tensor components with respect to radius.
	 * @param liGSecondDerivative
	 *   The second differential of the metric tensor components with respect to radius.
	 */
	private void calculateAllDifferentialsForAllValues(
	 List<MetricComponents> liG,
	 List<MetricComponents> liGFirstDerivative,
	 List<MetricComponents> liGSecondDerivative)
	{
		final int N = liG.size() - 1;
		DerivativeLevel[] adlDerivativeLevel = {First, Second};

		for (int i = 0; i <= N; i++)
		{
			MetricComponents mc1 = liGFirstDerivative.get(i);
			mc1.setA(0.0);
			mc1.setB(0.0);

			MetricComponents mc2 = liGSecondDerivative.get(i);
			mc2.setA(0.0);
			mc2.setB(0.0);
		}

		for (MetricComponent mcMetricComponent: MetricComponent.values())
			for (DerivativeLevel dlDerivativeLevel: adlDerivativeLevel)
				for (int i = 0; i <= N; i++)
					calculateDifferentialOfMetricComponent(liG, liGFirstDerivative, liGSecondDerivative, dlDerivativeLevel, i,
					 mcMetricComponent);
	}

	/**
	 * Calculate the specified level of differential of the specified metric component and store it
	 * in the appropriate list supplied.
	 * <br>
	 * All of the list parameters must be not <code>null</code> and contain the same number of elements
	 * for the same radius values. This number of elements must be at least 3.
	 * @param liG
	 *   A list of the metric tensor values, in order of ascending adjacent radius values.
	 * @param liGFirstDerivative
	 *   A list of first derivative metric tensor values, in order of ascending adjacent radius values.
	 * @param liGSecondDerivative
	 *   A list of second derivative metric tensor values, in order of ascending adjacent radius values.
	 * @param dlDerivativeLevel
	 *   The derivative level to be calculated.
	 * @param nIndex
	 *   The zero-based index value of the metric component, the differential of which is to be calculated.
	 * @param mcMetricComponent
	 *   The metric component, the differential of which is to be calculated.
	 */
	private void calculateDifferentialOfMetricComponent(
	 List<MetricComponents> liG,
	 List<MetricComponents> liGFirstDerivative,
	 List<MetricComponents> liGSecondDerivative,
	 DerivativeLevel dlDerivativeLevel, int nIndex,
	 MetricComponent mcMetricComponent)
	{
			double dblValue = differentialOfMetricComponent(liG, dlDerivativeLevel, nIndex, mcMetricComponent);

			setMetricComponentOfDerivativeLevel(liG, liGFirstDerivative,
			 liGSecondDerivative, dlDerivativeLevel, nIndex, mcMetricComponent,
			 dblValue);
	}

	/**
	 * Calculate the specified level of differential of the specified metric component.
	 * <br>
	 * All of the list parameters must be not <code>null</code> and contain the same number of elements
	 * for the same radius values. This number of elements must be at least 3.
	 * @param liG
	 *   A list of the metric tensor values, in order of ascending adjacent radius values.
	 * @param dlDerivativeLevel
	 *   The derivative level to be calculated.
	 * @param nIndex
	 *   The zero-based index value of the metric component, the differential of which is to be calculated.
	 * @param mcMetricComponent
	 *   The metric component, the differential of which is to be calculated.
	 * @return
	 *   The specified level of differential of the specified metric component with respect to radius,
	 *   calculated at or near the radius of the entry of the list of the given index.
	 */
	private double differentialOfMetricComponent(
	 List<MetricComponents> liG, DerivativeLevel dlDerivativeLevel, int nIndex, MetricComponent mcMetricComponent)
	{
		double dblResult = 0.0;
		final int N = liG.size() - 1;    // The maximum index value

		// The middle elements (index 1) are those of the point, the derivatives of which are to be calculated.
		// This may be different from the index supplied if it is the first or last point.
		// In these cases, we shall use forward and backward differences instead, respectively.
		double[] adblR = new double[3];
		double[] adblX = new double[3];

		final int N_START;
		if (nIndex == 0)
			N_START = nIndex;        // Forward difference for the first point
		else if (nIndex < N)
			N_START = nIndex - 1;    // Central difference for the internal points
		else
			N_START = nIndex - 2;    // Backward difference for the last point

		final int N_FINISH = N_START + 2;
		int n = 0;                       // The array index

		// Load the arrays
		for (int i = N_START; i <= N_FINISH; i++)
		{
			Entry<Double, Double> entry = getMetricComponentOfDerivativeLevel(liG, null, null, DerivativeLevel.None, i,
			 mcMetricComponent);
			adblR[n] = entry.getKey();
			adblX[n] = entry.getValue();
			n++;
		}

		double dblFirstDifferentialNext = (adblX[2] - adblX[1]) / (adblR[2] - adblR[1]);
		double dblFirstDifferentialPrev = (adblX[1] - adblX[0]) / (adblR[1] - adblR[0]);

		switch (dlDerivativeLevel)
		{
			case First:
				dblResult = 0.5 * (dblFirstDifferentialNext + dblFirstDifferentialPrev);
				break;
			case Second:
				dblResult = 2.0 * (dblFirstDifferentialNext - dblFirstDifferentialPrev) / (adblR[2] - adblR[0]);
				break;
			default:
				throw new RuntimeException(String.format(
				 "Invalid differentiation request for:"
					+ "%n  dlDerivativeLevel = \"%s\","
					+ "%n  mcMetricComponent = \"%s\","
					+ "%n  nIndex            = %d,"
					+ "%n  N                 = %d.",
				 dlDerivativeLevel.toString(), mcMetricComponent.toString(), nIndex, N));
		}

		return dblResult;
	}

	/**
	 * Get the specified radius and metric component of the specified level of differential of the specified index
	 * from the lists supplied.
	 * <br>
	 * The list parameter for the derivative level sought must be not <code>null</code> and must contain the same number
	 * of elements for the same radius values as any other list parameter used.
	 * @param liG
	 *   A list of the metric tensor values, in order of ascending adjacent radius values.
	 * @param liGFirstDerivative
	 *   A list of first derivative metric tensor values, in order of ascending adjacent radius values.
	 * @param liGSecondDerivative
	 *   A list of second derivative metric tensor values, in order of ascending adjacent radius values.
	 * @param dlDerivativeLevel
	 *   The derivative level to be found.
	 * @param nIndex
	 *   The zero-based index value of the metric component to be found.
	 * @param mcMetricComponent
	 *   The metric component to be found.
	 * @return
	 *   The specified radius and metric component of the specified level of differential of the specified index
	 *   from the lists supplied.
	 */
	public static Entry<Double, Double> getMetricComponentOfDerivativeLevel(
	 List<MetricComponents> liG,
	 List<MetricComponents> liGFirstDerivative,
	 List<MetricComponents> liGSecondDerivative,
	 DerivativeLevel dlDerivativeLevel, int nIndex, MetricComponent mcMetricComponent)
	{
		MetricComponents mcMetricComponents =
		 getMetricComponents(liG, liGFirstDerivative, liGSecondDerivative, dlDerivativeLevel, nIndex);
		Entry<Double, Double> entryResult = mcMetricComponents.getComponent(mcMetricComponent);
		return entryResult;
	}

	/**
	 * Set the specified metric component of the specified level of differential of the specified index
	 * using the lists supplied.
	 * <br>
	 * All of the list parameters must be not <code>null</code> and contain the same number of elements
	 * for the same radius values.
	 * @param liG
	 *   A list of the metric tensor values, in order of ascending adjacent radius values.
	 * @param liGFirstDerivative
	 *   A list of first derivative metric tensor values, in order of ascending adjacent radius values.
	 * @param liGSecondDerivative
	 *   A list of second derivative metric tensor values, in order of ascending adjacent radius values.
	 * @param dlDerivativeLevel
	 *   The derivative level to be set.
	 * @param nIndex
	 *   The zero-based index value of the metric component to be set.
	 * @param mcMetricComponent
	 *   The metric component to be set.
	 * @param dblValue
	 *   The metric component value to be set.
	 */
	public static void setMetricComponentOfDerivativeLevel(
	 List<MetricComponents> liG,
	 List<MetricComponents> liGFirstDerivative,
	 List<MetricComponents> liGSecondDerivative,
	 DerivativeLevel dlDerivativeLevel, int nIndex, MetricComponent mcMetricComponent, double dblValue)
	{
		MetricComponents mcMetricComponents =
		 getMetricComponents(liG, liGFirstDerivative, liGSecondDerivative, dlDerivativeLevel, nIndex);
		mcMetricComponents.setComponent(mcMetricComponent, dblValue);
	}

	/**
	 * Obtain the <code>MetricComponents</code> for the given parameters.
	 * @param liG
	 *   A list of the metric tensor values, in order of ascending adjacent radius values.
	 * @param liGFirstDerivative
	 *   A list of first derivative metric tensor values, in order of ascending adjacent radius values.
	 * @param liGSecondDerivative
	 *   A list of second derivative metric tensor values, in order of ascending adjacent radius values.
	 * @param dlDerivativeLevel
	 *   The derivative level.
	 * @param nIndex
	 *   The zero-based index value of the metric component.
	 * @return
	 *   The <code>MetricComponents</code>.
	 */
	private static MetricComponents getMetricComponents(
	 List<MetricComponents> liG,
	 List<MetricComponents> liGFirstDerivative,
	 List<MetricComponents> liGSecondDerivative,
	 DerivativeLevel dlDerivativeLevel, int nIndex)
	{
		MetricComponents mcMetricComponents = null;

		switch (dlDerivativeLevel)
		{
			case None:
				mcMetricComponents = liG.get(nIndex);
				break;
			case First:
				mcMetricComponents = liGFirstDerivative.get(nIndex);
				break;
			case Second:
				mcMetricComponents = liGSecondDerivative.get(nIndex);
				break;
			default:
				throw new RuntimeException(String.format(
				 "Derivative level \"%s\" not found.", dlDerivativeLevel.toString()));
		}

		return mcMetricComponents;
	}

	/*
	 * Calculate the Jacobian matrix (values) at the given point in space-time (the radius).
	 * <br>
	 * All of the list parameters must be not <code>null</code> and contain the
	 * same number of elements for the same radius values.
	 * This number of elements must be at least 5.
	 * @param liG
	 *   A list of the metric tensor values, in order of ascending adjacent radius values.
	 * @param liGFirstDerivative
	 *   A list of first derivative metric tensor values, in order of ascending adjacent radius values.
	 * @param liGSecondDerivative
	 *   A list of second derivative metric tensor values, in order of ascending adjacent radius values.
	 * @param nIndex
	 *   The zero-based index of the metric component of the point in space-time (the radius) to be used.
	 * @return
	 *   The Jacobian matrix (values) at the given point in space-time (the radius).
	 */
	/*
	private DoubleMatrix2D calculateJacobianMatrixValues(List<MetricComponents> liG,
	 List<MetricComponents> liGFirstDerivative, List<MetricComponents> liGSecondDerivative, int nIndex)
	{
		Entry<Double, Double> entry = getMetricComponentOfDerivativeLevel(liG, liGFirstDerivative, liGSecondDerivative,
		 None, nIndex, A);
		double dblR = entry.getKey().doubleValue();
		double dblA = entry.getValue().doubleValue();

		double dblB = getMetricComponentOfDerivativeLevel(liG, liGFirstDerivative, liGSecondDerivative, None, nIndex, B).
		 getValue().doubleValue();

		double dAdR = getMetricComponentOfDerivativeLevel(liG, liGFirstDerivative, liGSecondDerivative, First, nIndex, A)
		 .getValue().doubleValue();

		double dBdR = getMetricComponentOfDerivativeLevel(liG, liGFirstDerivative, liGSecondDerivative, First, nIndex, B)
		 .getValue().doubleValue();

		double d2AdR2 = getMetricComponentOfDerivativeLevel(liG, liGFirstDerivative, liGSecondDerivative, Second, nIndex, A)
		 .getValue().doubleValue();

		double dR00dA = (1 / (4.0 * dblA * dblA * dblB)) * dAdR * dAdR;
		double dR00dB = -((1 / (dblB * dblB * dblR)) * dAdR) + ((1 / (4.0 * dblA * dblB * dblB)) * dAdR * dAdR)
		 + ((1 / (2.0 * dblB * dblB * dblB)) * dAdR * dBdR) - ((1 / (2.0 * dblB * dblB)) * d2AdR2);

		double dR11dA = ((1 / (4.0 * dblA * dblA * dblB)) * dAdR * dBdR) + ((1 / (2.0 * dblA * dblA * dblA)) * dAdR * dAdR)
		 - ((1 / (2.0 * dblA * dblA)) * d2AdR2);
		double dR11dB = ((1 / (dblB * dblB * dblR)) * dBdR) + ((1 / (4.0 * dblA * dblB * dblB)) * dAdR * dBdR);

		double dR22dA = ((dblR / (2 * dblA * dblA * dblB)) * dAdR);
		double dR22dB = (1 / (dblB * dblB)) + ((dblR / (2 * dblA * dblB * dblB)) * dAdR)
		 - ((dblR / (dblB * dblB * dblB)) * dBdR);

		DoubleMatrix2D dmResult = DoubleFactory2D.dense.make(3, 2);
		dmResult.set(0, 0, dR00dA);
		dmResult.set(1, 0, dR11dA);
		dmResult.set(2, 0, dR22dA);
		dmResult.set(0, 1, dR00dB);
		dmResult.set(1, 1, dR11dB);
		dmResult.set(2, 1, dR22dB);
		return dmResult;
	}
	*/

	/**
	 * Calculate the Ricci tensor values at the given point in space-time (the radius).
	 * <br>
	 * All of the list parameters must be not <code>null</code> and contain the
	 * same number of elements for the same radius values.
	 * This number of elements must be at least 5.
	 * @param liG
	 *   A list of the metric tensor values, in order of ascending adjacent radius values.
	 * @param liGFirstDerivative
	 *   A list of first derivative metric tensor values, in order of ascending adjacent radius values.
	 * @param liGSecondDerivative
	 *   A list of second derivative metric tensor values, in order of ascending adjacent radius values.
	 * @param nIndex
	 *   The zero-based index of the metric component of the point in space-time (the radius) to be used.
	 * @return
	 *   The Ricci tensor values at the given point in space-time (the radius) as the vector (1-D column matrix):
	 *   <code>(R00, R11, R22)T</code>.
	 */
	public static DoubleMatrix2D calculateRicciTensorValues(List<MetricComponents> liG,
	 List<MetricComponents> liGFirstDerivative, List<MetricComponents> liGSecondDerivative, int nIndex)
	{
		Entry<Double, Double> entry = getMetricComponentOfDerivativeLevel(liG, liGFirstDerivative, liGSecondDerivative,
		 None, nIndex, A);
		double dblR = entry.getKey();
		double dblA = entry.getValue();

		double dblB = getMetricComponentOfDerivativeLevel(liG, liGFirstDerivative, liGSecondDerivative, None, nIndex, B).getValue();

		double dAdR = getMetricComponentOfDerivativeLevel(liG, liGFirstDerivative, liGSecondDerivative, First, nIndex, A).getValue();

		double dBdR = getMetricComponentOfDerivativeLevel(liG, liGFirstDerivative, liGSecondDerivative, First, nIndex, B).getValue();

		double d2AdR2 = getMetricComponentOfDerivativeLevel(liG, liGFirstDerivative, liGSecondDerivative, Second, nIndex, A).getValue();

		double dblR00 = ((1.0 / (dblB * dblR)) * dAdR)
		 - ((1.0 / (4.0 * dblA * dblB)) * dAdR * dAdR)
		 - ((1.0 / (4.0 * dblB * dblB)) * dAdR * dBdR)
		 + ((1.0 / (2.0 * dblB)) * d2AdR2);

		double dblR11 = -((1.0 / (dblB * dblR)) * dBdR)
		 - ((1.0 / (4.0 * dblA * dblB)) * dAdR * dBdR)
		 - ((1.0 / (4.0 * dblA * dblA)) * dAdR * dAdR)
		 + ((1.0 / (2.0 * dblA)) * d2AdR2);

		double dblR22 = -1.0 - (1.0 / dblB)
		 - ((dblR / (2.0 * dblA * dblB)) * dAdR)
		 + ((dblR / (2.0 * dblB * dblB)) * dBdR);

		DoubleMatrix2D dvResult = DoubleFactory2D.dense.make(3, 1);
		dvResult.set(0, 0, dblR00);
		dvResult.set(1, 0, dblR11);
		dvResult.set(2, 0, dblR22);
		return dvResult;
	}

	private void reportFinalTensorValues()
	{
		logger.info(String.format("The metric components (in the format \"index, r, A, B\") after the final run are:"));
		StringBuilder sbLog = new StringBuilder();

		// For use in CSV format
 // sbLog.append(String.format(
 // 	 "%n      i,                  R,                  A,                  B,              dA/dR,              dB/dR,            d2A/dR2,            d2B/dR2"
 //  + "%n"));

		sbLog.append(String.format(
			 "%n      i                   R                   A                   B               dA/dR               dB/dR             d2A/dR2             d2B/dR2"
		 + "%n  -----  ------------------  ------------------  ------------------  ------------------  ------------------  ------------------  ------------------"));

		for (int i = 0; i < m_liG.size(); i++)
		{
			Entry<Double, Double> entry = getMetricComponentOfDerivativeLevel(m_liG, null, null, None, i, A);
			double dblR = entry.getKey();
			double dblA = entry.getValue();
			double dblB = getMetricComponentOfDerivativeLevel(m_liG, null, null, None, i, B).getValue();

			double dAdR = getMetricComponentOfDerivativeLevel(
			 m_liG, m_liGFirstDerivative, m_liGSecondDerivative, First, i, A).getValue();
			double dBdR = getMetricComponentOfDerivativeLevel(
			 m_liG, m_liGFirstDerivative, m_liGSecondDerivative, First, i, B).getValue();
			double d2AdR2 = getMetricComponentOfDerivativeLevel(
			 m_liG, m_liGFirstDerivative, m_liGSecondDerivative, Second, i, A).getValue();
			double d2BdR2 = getMetricComponentOfDerivativeLevel(
			 m_liG, m_liGFirstDerivative, m_liGSecondDerivative, Second, i, B).getValue().doubleValue();

	 // String sFormat = "%n  %5d, %,18.12f, %,18.12f, %,18.12f, %,18.12f, %,18.12f, %,18.12f, %,18.12f";    // For use in CSV format
			String sFormat = "%n  %5d  %,18.12f  %,18.12f  %,18.12f  %,18.12f  %,18.12f  %,18.12f  %,18.12f";

			sbLog.append(String.format(sFormat, i, dblR, dblA, dblB, dAdR, dBdR, d2AdR2, d2BdR2));
		}

		logger.info(sbLog.toString());
	}
}
