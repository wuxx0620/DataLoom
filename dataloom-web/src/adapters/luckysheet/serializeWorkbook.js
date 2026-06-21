function dataMatrixToCelldata(data) {
  const celldata = []
  data.forEach((row, r) => {
    if (!Array.isArray(row)) return
    row.forEach((cell, c) => {
      if (isEmptyCell(cell)) return
      celldata.push({ r, c, v: cell })
    })
  })
  return celldata
}

function normalizeCelldata(celldata) {
  if (!Array.isArray(celldata)) return []
  return celldata.filter((cell) => cell && !isEmptyCell(cell.v))
}

function isEmptyCell(cell) {
  if (!cell) return true
  if (typeof cell === 'object') return Object.keys(cell).length === 0
  return false
}

function calcMaxRow(celldata) {
  return celldata.reduce((max, cell) => Math.max(max, Number(cell.r || 0) + 1), 1)
}

function calcMaxColumn(data, celldata) {
  const dataCols = data.reduce((max, row) => Math.max(max, Array.isArray(row) ? row.length : 0), 0)
  const cellCols = celldata.reduce((max, cell) => Math.max(max, Number(cell.c || 0) + 1), 0)
  return Math.max(dataCols, cellCols, 1)
}

function recoverChartOptionsFromDom(sheets) {
  sheets.forEach((sheet) => {
    if (!Array.isArray(sheet.chart) || sheet.chart.length === 0) return

    sheet.chart.forEach((chartItem) => {
      if (!chartItem || !chartItem.chart_id) return
      if (chartItem.chartOptions && typeof chartItem.chartOptions === 'object'
          && Object.keys(chartItem.chartOptions).length > 0) {
        return
      }

      try {
        if (!window.echarts) return
        const candidateIds = [chartItem.chart_id, `${chartItem.chart_id}_c`]
        let echartsInstance = null

        for (const domId of candidateIds) {
          const dom = document.getElementById(domId)
          if (!dom) continue
          echartsInstance = window.echarts.getInstanceByDom(dom)
          if (echartsInstance) break

          const children = dom.querySelectorAll('div[_echarts_instance_], canvas')
          for (const child of children) {
            echartsInstance = window.echarts.getInstanceByDom(child.parentElement || child)
            if (echartsInstance) break
          }
          if (echartsInstance) break
        }

        if (echartsInstance) {
          chartItem.chartOptions = echartsInstance.getOption()
        }
      } catch (error) {
        console.warn(`[serializeWorkbook] ECharts 恢复 chart[${chartItem.chart_id}] 失败:`, error.message)
      }
    })
  })
}

function cleanupZombieCharts(sheets) {
  let cleanedCount = 0
  sheets.forEach((sheet) => {
    if (!Array.isArray(sheet.chart) || sheet.chart.length === 0) return
    const before = sheet.chart.length
    sheet.chart = sheet.chart.filter((chartItem) => {
      if (!chartItem || !chartItem.chart_id) return false
      return chartItem.chartOptions
        && typeof chartItem.chartOptions === 'object'
        && Object.keys(chartItem.chartOptions).length > 0
    })
    cleanedCount += before - sheet.chart.length
  })
  return cleanedCount
}

/**
 * 将 Luckysheet 内存状态序列化为后端 saveWorkbook 所需格式。
 */
export function serializeLuckysheetWorkbook(luckysheet) {
  const rawSheets = luckysheet?.getluckysheetfile?.() || luckysheet?.getAllSheets?.()
  const sheets = Array.isArray(rawSheets) ? rawSheets : rawSheets ? [rawSheets] : []

  recoverChartOptionsFromDom(sheets)
  const cleanedCount = cleanupZombieCharts(sheets)
  if (cleanedCount > 0) {
    console.warn(`[serializeWorkbook] 清理了 ${cleanedCount} 个无法恢复的僵尸 chart 数据`)
  }

  try {
    luckysheet?.getluckysheetfile?.(true)
  } catch (error) {
    console.warn('[serializeWorkbook] getluckysheetfile(true) 失败:', error.message)
  }

  return sheets.map((sheet, index) => {
    const data = Array.isArray(sheet.data) ? sheet.data : []
    const celldata = data.length > 0 ? dataMatrixToCelldata(data) : normalizeCelldata(sheet.celldata || [])

    let safeChart = []
    if (Array.isArray(sheet.chart)) {
      safeChart = sheet.chart
        .filter((chartItem) => chartItem && chartItem.chart_id
          && chartItem.chartOptions && Object.keys(chartItem.chartOptions).length > 0)
        .map((chartItem) => {
          const chart = { ...chartItem }
          if (!chart.chartType) {
            let chartType = 'line'
            const series = chart.chartOptions?.series
            if (Array.isArray(series) && series.length > 0) {
              chartType = series[0]?.type || 'line'
            }
            chart.chartType = `echarts|${chartType}|default`
          }
          return chart
        })
    }

    return {
      ...sheet,
      name: sheet.name || `Sheet${index + 1}`,
      order: sheet.order ?? index,
      row: sheet.row || data.length || calcMaxRow(celldata),
      column: sheet.column || calcMaxColumn(data, celldata),
      config: sheet.config || { merge: {}, columnlen: {}, rowlen: {} },
      celldata,
      chart: safeChart
    }
  })
}
