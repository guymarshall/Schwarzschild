package ianmarshall;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

/**
 * This class represents elements of the metric or fundamental tensor at a point in space-time.
 * For the Schwarzschild approximation, this point in space-time is given by the radius only.
 */
public class MetricComponents implements Cloneable
{
	public enum MetricComponent
	{
		A, B
	}

	/**
	 * These are the Ricci tensor components which can be non-zero.
	 * R33 is excluded since this is simply R22 * ((sin theta)^2).
	 */
	public enum RicciTensor
	{
		R00, R11, R22
	}

	private double m_R = 0.0;    // The radius co-ordinate of the metric.
	private double m_A = 0.0;    // } The component values
	private double m_B = 0.0;    // } of the metric.

	public MetricComponents(double r, double a, double b)
	{
		m_R = r;
		m_A = a;
		m_B = b;
	}

	@Override
	public MetricComponents clone() throws CloneNotSupportedException
	{
		MetricComponents mcResult = (MetricComponents)super.clone();
		mcResult.setR(getR());
		mcResult.setA(getA());
		mcResult.setB(getB());
		return mcResult;
	}

	public double getR()
	{
		return m_R;
	}

	public void setR(double r)
	{
		m_R = r;
	}

	public double getA()
	{
		return m_A;
	}

	public void setA(double a)
	{
		m_A = a;
	}

	public double getB()
	{
		return m_B;
	}

	public void setB(double b)
	{
		m_B = b;
	}

	public Entry<Double, Double> getComponent(MetricComponent mc)
	{
		double dbl = 0.0;

		switch (mc)
			{
				case A:
					dbl = getA();
					break;
				case B:
					dbl = getB();
					break;
				default:
					throw new IllegalArgumentException(String.format("Metric component \"%s\" not found.", mc.toString()));
			}

		Entry<Double, Double> entryResult = new SimpleEntry<>(getR(), dbl);
		return entryResult;
	}

	public void setComponent(MetricComponent mc, double dbl)
	{
		switch (mc)
		{
			case A:
				setA(dbl);
				break;
			case B:
				setB(dbl);
				break;
			default:
				throw new IllegalArgumentException(String.format("Metric component \"%s\" not found.", mc.toString()));
		}
	}

	/**
	 * Make a deep copy of a list of <code>MetricComponents</code>.
	 * @param liG
	 *   The list of <codeMetricComponents</code> to be copied.
	 *   If this is <code>null</code> then an empty list will be returned.
	 * @return
	 *   A deep copy of the list supplied.
	 */
	public static List<MetricComponents> deepCopyMetricComponents(List<MetricComponents> liG)
	{
		int nSize = liG != null ? liG.size() : 0;
		List<MetricComponents> liResult = new ArrayList<>(nSize);

		if ((liG != null) && !liG.isEmpty())
			for (MetricComponents mc: liG)
			{
				MetricComponents mcNew;
				try
				{
					mcNew = mc.clone();
				}
				catch (CloneNotSupportedException e)
				{
					throw new RuntimeException(e);    // This should never happen
				}

				liResult.add(mcNew);
			}

		return liResult;
	}
}
