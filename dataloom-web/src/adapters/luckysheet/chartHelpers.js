import { defaultOption } from '@/utils/chartmixDefaultOption'
import { resolveSheetCalcChain } from '@/adapters/luckysheet/formulaHelpers'

/**
 * 补全 Luckysheet chartmix 所需的 chart 字段，避免插件初始化崩溃。
 */
export function normalizeChartsForInit(charts = []) {
  return (charts || []).map((chartItem) => {
    if (!chartItem) return chartItem

    const chart = { ...chartItem }
    let chartType = 'line'
    const series = chart.chartOptions?.series
    if (Array.isArray(series) && series.length > 0) {
      chartType = series[0]?.type || 'line'
    }
    const fullChartType = `echarts|${chartType}|default`

    chart.chartOptions = chart.chartOptions || {}
    if (!chart.chartType) chart.chartType = fullChartType
    if (!chart.chartOptions.chart_id) chart.chartOptions.chart_id = chart.chart_id
    if (!chart.chartOptions.chartAllType) chart.chartOptions.chartAllType = fullChartType
    if (!chart.chartOptions.rangeArray) {
      chart.chartOptions.rangeArray = [{ row: [0, 0], column: [0, 0] }]
    }
    if (!chart.chartOptions.rangeColCheck) {
      chart.chartOptions.rangeColCheck = { exits: true, clear: [0, 0], type: ['number', 'number'] }
    }
    if (!chart.chartOptions.rangeRowCheck) {
      chart.chartOptions.rangeRowCheck = { exits: true, clear: [0, 0], type: ['string', 'number'] }
    }
    if (chart.chartOptions.rangeConfigCheck === undefined) {
      chart.chartOptions.rangeConfigCheck = false
    }
    if (!chart.chartOptions.chartDataSeriesOrder) {
      chart.chartOptions.chartDataSeriesOrder = { 0: 0, length: 1 }
    }
    if (!chart.chartOptions.defaultOption) {
      chart.chartOptions.defaultOption = JSON.parse(JSON.stringify(defaultOption || {}))
    }

    return chart
  })
}

export function buildLuckysheetCreatePayload(sheets) {
  return sheets.map((sheet, index) => {
    const sheetIndex = sheet.index != null ? String(sheet.index) : String(index)
    return {
      name: sheet.name || `Sheet${index + 1}`,
      index: sheetIndex,
      status: sheet.status ?? (index === 0 ? 1 : 0),
      order: index,
      celldata: sheet.celldata || [],
      calcChain: resolveSheetCalcChain(sheet, sheetIndex),
      config: sheet.config || { merge: {}, columnlen: {}, rowlen: {} },
      hyperlink: sheet.hyperlink || {},
      images: sheet.images || {},
      luckysheet_conditionformat_save: sheet.luckysheet_conditionformat_save || [],
      chart: normalizeChartsForInit(sheet.chart)
    }
  })
}
