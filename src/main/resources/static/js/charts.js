// Single-series charts: one accent colour, no legend (the card title names the series),
// labels in text colours, faint gridlines, 2px lines, rounded bar ends and tooltips on tap.
function tokens() {
  const css = getComputedStyle(document.documentElement);
  const read = (name) => css.getPropertyValue(name).trim();
  return { series: read('--series-1'), text: read('--text-secondary'), grid: read('--grid'), surface: read('--surface-1') };
}

function baseOptions(t, unit) {
  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    plugins: {
      legend: { display: false },
      tooltip: { displayColors: false, intersect: false, mode: 'index', callbacks: { label: (c) => `${c.formattedValue} ${unit}` } },
    },
    scales: {
      x: { grid: { display: false }, border: { color: t.grid }, ticks: { color: t.text, maxRotation: 0, autoSkipPadding: 12 } },
      y: { grid: { color: t.grid }, border: { display: false }, ticks: { color: t.text } },
    },
  };
}

export function lineChart(canvas, labels, values, unit) {
  const { Chart } = window;
  Chart.getChart(canvas)?.destroy();
  const t = tokens();
  return new Chart(canvas, {
    type: 'line',
    data: {
      labels,
      datasets: [{
        data: values,
        borderColor: t.series,
        backgroundColor: t.series,
        borderWidth: 2,
        pointRadius: 4,
        pointHoverRadius: 6,
        pointBorderColor: t.surface,
        pointBorderWidth: 2,
      }],
    },
    options: baseOptions(t, unit),
  });
}

export function barChart(canvas, labels, values, { unit, horizontal = false }) {
  const { Chart } = window;
  Chart.getChart(canvas)?.destroy();
  const t = tokens();
  const options = baseOptions(t, unit);
  const valueAxis = { ...options.scales.y, beginAtZero: true, ticks: { ...options.scales.y.ticks, precision: 0 } };
  const categoryAxis = options.scales.x;
  options.scales = horizontal ? { x: valueAxis, y: categoryAxis } : { x: categoryAxis, y: valueAxis };
  if (horizontal) options.indexAxis = 'y';
  return new Chart(canvas, {
    type: 'bar',
    data: { labels, datasets: [{ data: values, backgroundColor: t.series, borderRadius: 4, borderSkipped: 'start', maxBarThickness: 28 }] },
    options,
  });
}
