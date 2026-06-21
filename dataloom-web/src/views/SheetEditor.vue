<template>
  <main class="editor-page">
    <header class="editor-toolbar">
      <div class="toolbar-left">
        <el-button :icon="ArrowLeft" @click="goBack">返回列表</el-button>
        <div class="doc-meta">
          <strong
            v-if="!editingName"
            class="doc-name"
            title="点击重命名"
            @click="startRename"
          >{{ documentName || '未命名文档' }}</strong>
          <div v-else class="doc-name-edit">
            <input
              ref="nameInput"
              v-model="renameValue"
              class="rename-input"
              maxlength="200"
              @keydown.enter="confirmRename"
              @keydown.escape="cancelRename"
              @blur="confirmRename"
            />
          </div>
          <span v-if="loadingSheet">加载 Sheet 数据：{{ loadedChunks }}/{{ totalChunks }} 块</span>
          <span v-else>{{ sheetCount }} 个 Sheet</span>
        </div>
      </div>

      <div class="toolbar-right">
        <el-tag v-if="hasUnsavedChanges" type="warning" effect="light">有未保存修改</el-tag>
        <el-button type="primary" :icon="Upload" :loading="saving" @click="saveChanges">保存</el-button>
        <el-button type="success" :icon="Download" :loading="exporting" @click="exportCurrentWorkbook">
          导出
        </el-button>
      </div>
    </header>

    <section class="sheet-stage">
      <div v-if="booting" class="boot-panel">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>正在打开表格...</span>
      </div>
      <div :id="spreadsheetContainerId"></div>
    </section>
  </main>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Download, Loading, Upload } from '@element-plus/icons-vue'
import { ElLoading, ElMessage } from 'element-plus'
import { batchUpdateCells, getDocument, loadAllCelldata, renameDocument, saveWorkbook } from '@/api/excel'
import { createSpreadsheetAdapter, SPREADSHEET_CONTAINER_ID, SPREADSHEET_ENGINES } from '@/adapters/spreadsheet'
import { exportExcel } from '@/utils/export'

const route = useRoute()
const router = useRouter()

const spreadsheetContainerId = SPREADSHEET_CONTAINER_ID
const documentId = ref(route.params.id)
const documentName = ref('')
const sheetCount = ref(0)
const booting = ref(true)
const loadingSheet = ref(false)
const loadedChunks = ref(0)
const totalChunks = ref(0)
const saving = ref(false)
const exporting = ref(false)
const workbookDirty = ref(false)
const structureDirty = ref(false)
const sheetIdMap = reactive({})
const dirtyCells = reactive({})

let spreadsheetAdapter = null

const hasUnsavedChanges = computed(() =>
  workbookDirty.value || structureDirty.value || Object.keys(dirtyCells).length > 0
)

const editingName = ref(false)
const renameValue = ref('')
const nameInput = ref(null)

function createAdapter() {
  return createSpreadsheetAdapter(SPREADSHEET_ENGINES.LUCKYSHEET, {
    containerId: spreadsheetContainerId,
    getSheetIdMap: () => sheetIdMap,
    onCellDirty: ({ sheetId, r, c, v }) => {
      dirtyCells[`${sheetId}_${r}_${c}`] = { sheetId, r, c, v }
    },
    onWorkbookDirty: () => {
      workbookDirty.value = true
    },
    onStructureDirty: () => {
      structureDirty.value = true
    }
  })
}

function resetDirtyState() {
  workbookDirty.value = false
  structureDirty.value = false
  Object.keys(dirtyCells).forEach((key) => delete dirtyCells[key])
}

function startRename() {
  renameValue.value = documentName.value
  editingName.value = true
  setTimeout(() => nameInput.value?.focus(), 50)
}

async function confirmRename() {
  if (!editingName.value) return
  const newName = renameValue.value.trim()
  editingName.value = false

  if (!newName || newName === documentName.value) return

  try {
    await renameDocument(documentId.value, newName)
    documentName.value = newName
    ElMessage.success('重命名成功')
  } catch (error) {
    ElMessage.error(`重命名失败：${error.message}`)
  }
}

function cancelRename() {
  editingName.value = false
}

onMounted(async () => {
  spreadsheetAdapter = createAdapter()
  await initDocument()
})

onBeforeUnmount(() => {
  spreadsheetAdapter?.destroy()
  spreadsheetAdapter = null
})

async function initDocument() {
  const loading = ElLoading.service({
    lock: true,
    text: '正在加载文档结构...',
    background: 'rgba(255,255,255,0.78)'
  })

  try {
    const response = await getDocument(documentId.value)
    const payload = response.data
    if (!payload?.success) throw new Error(payload?.message || '文档不存在')

    const doc = payload.data
    documentId.value = doc.id
    documentName.value = doc.name

    const metas = doc.sheets || []
    sheetCount.value = metas.length
    metas.forEach((meta, index) => {
      sheetIdMap[String(index)] = meta.sheetId
    })

    const sheets = buildInitialSheets(metas)
    for (let index = 0; index < metas.length; index += 1) {
      const meta = metas[index]
      loading.setText(`正在加载 ${meta.sheetName || `Sheet${index + 1}`}...`)
      sheets[index].celldata = await fetchSheetCelldata(meta)
    }

    loading.close()
    await nextTick()

    try {
      spreadsheetAdapter?.init(sheets)
      resetDirtyState()
    } catch (error) {
      ElMessage.error(error.message)
    }
  } catch (error) {
    loading.close()
    ElMessage.error(`打开文档失败：${error.message}`)
    router.push({ name: 'Dashboard' })
  } finally {
    booting.value = false
  }
}

function buildInitialSheets(metas) {
  if (metas.length === 0) {
    return [{
      name: 'Sheet1',
      index: '0',
      status: 1,
      order: 0,
      celldata: [],
      config: { merge: {}, columnlen: {}, rowlen: {} },
      hyperlink: {},
      images: {},
      luckysheet_conditionformat_save: [],
      chart: []
    }]
  }

  return metas.map((meta, index) => {
    const config = meta.config && Object.keys(meta.config).length > 0
      ? meta.config
      : {
          merge: meta.mergeConfig || {},
          columnlen: meta.columnLen || {},
          rowlen: meta.rowLen || {}
        }

    return {
      name: meta.sheetName || `Sheet${index + 1}`,
      index: String(index),
      status: index === 0 ? 1 : 0,
      order: index,
      celldata: [],
      config,
      _sheetId: meta.sheetId,
      hyperlink: meta.hyperlink || {},
      images: meta.images || {},
      luckysheet_conditionformat_save: meta.luckysheet_conditionformat_save || [],
      chart: meta.chart || []
    }
  })
}

async function fetchSheetCelldata(meta) {
  loadingSheet.value = true
  loadedChunks.value = 0
  totalChunks.value = meta.chunkCount || 1

  try {
    const response = await loadAllCelldata(documentId.value, meta.sheetId)
    const payload = response.data
    if (!payload?.success) throw new Error(payload?.message || 'Sheet 数据加载失败')

    loadedChunks.value = totalChunks.value
    return payload.data?.celldata || []
  } catch (error) {
    ElMessage.warning(`${meta.sheetName || 'Sheet'} 加载失败：${error.message}`)
    return []
  } finally {
    loadingSheet.value = false
  }
}

async function saveChanges() {
  if (!spreadsheetAdapter?.isReady()) {
    ElMessage.error('表格尚未初始化')
    return
  }

  saving.value = true
  try {
    const structuralChange = spreadsheetAdapter.detectStructuralChange()
    const cellDirtyCount = Object.keys(dirtyCells).length

    if (structuralChange.changed) {
      console.log(`====== [saveChanges] 结构变更 [${structuralChange.reason}]，使用全量快照保存`)
      const sheets = spreadsheetAdapter.serializeWorkbook()
      if (!sheets || sheets.length === 0) {
        ElMessage.error('表格数据为空')
        return
      }

      const response = await saveWorkbook(documentId.value, sheets)
      const payload = response.data
      if (payload?.success && payload.data?.sheetIdMap) {
        const newIdMap = payload.data.sheetIdMap
        Object.keys(newIdMap).forEach((key) => {
          sheetIdMap[String(key)] = String(newIdMap[key])
        })
        sheetCount.value = payload.data.sheetCount || sheets.length
      } else {
        sheetCount.value = sheets.length
      }
    } else if (cellDirtyCount > 0) {
      console.log(`====== [saveChanges] 仅单元格变更，增量更新 ${cellDirtyCount} 个单元格`)
      const updates = Object.values(dirtyCells).map((item) => ({
        sheetId: Number(item.sheetId),
        r: item.r,
        c: item.c,
        v: item.v
      }))
      await batchUpdateCells(documentId.value, updates)
    } else if (workbookDirty.value) {
      console.log('====== [saveChanges] workbookDirty 但无可追踪变更，兜底全量保存')
      const sheets = spreadsheetAdapter.serializeWorkbook()
      if (sheets && sheets.length > 0) {
        const response = await saveWorkbook(documentId.value, sheets)
        const payload = response.data
        if (payload?.success && payload.data?.sheetIdMap) {
          const newIdMap = payload.data.sheetIdMap
          Object.keys(newIdMap).forEach((key) => {
            sheetIdMap[String(key)] = String(newIdMap[key])
          })
          sheetCount.value = payload.data.sheetCount || sheets.length
        } else {
          sheetCount.value = sheets.length
        }
      }
    } else {
      ElMessage.info('没有需要保存的修改')
      return
    }

    resetDirtyState()
    if (structuralChange.changed) {
      spreadsheetAdapter.captureStructureSnapshot()
    }

    ElMessage.success(structuralChange.changed
      ? `保存成功（全量），共 ${sheetCount.value} 个 Sheet`
      : `保存成功（增量），更新 ${cellDirtyCount} 个单元格`)
  } catch (error) {
    ElMessage.error(`保存失败：${error.message}`)
  } finally {
    saving.value = false
  }
}

async function exportCurrentWorkbook() {
  if (hasUnsavedChanges.value) {
    ElMessage.warning('请先保存当前修改后再导出')
    return
  }

  exporting.value = true
  try {
    const sheets = spreadsheetAdapter?.getSheetsForExport()
    if (!sheets) throw new Error('表格尚未初始化')

    await exportExcel(sheets, documentName.value)
    ElMessage.success('导出完成')
  } catch (error) {
    ElMessage.error(`导出失败：${error.message}`)
  } finally {
    exporting.value = false
  }
}

function goBack() {
  router.push({ name: 'Dashboard' })
}
</script>

<style scoped>
.editor-page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
  background: #eef2eb;
}

.editor-toolbar {
  z-index: 10;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 58px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--dl-line);
  background: rgba(255, 255, 255, 0.94);
  box-shadow: 0 8px 24px rgba(20, 32, 24, 0.08);
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.doc-meta {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.doc-meta strong,
.doc-meta span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-meta strong {
  max-width: 360px;
  font-size: 14px;
}

.doc-meta .doc-name {
  cursor: pointer;
  transition: color 0.15s;
}

.doc-meta .doc-name:hover {
  color: var(--dl-accent-strong);
}

.doc-name-edit {
  display: flex;
  align-items: center;
}

.rename-input {
  width: 280px;
  max-width: 200px;
  padding: 4px 8px;
  border: 1px solid var(--dl-accent-strong);
  border-radius: 4px;
  font-size: 14px;
  font-weight: 600;
  outline: none;
  background: #fff;
  box-shadow: 0 0 0 2px rgba(47, 125, 87, 0.12);
}

.doc-meta span {
  color: var(--dl-muted);
  font-size: 12px;
}

.sheet-stage {
  position: relative;
  flex: 1;
  min-height: 0;
}

#luckysheet-container {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.boot-panel {
  position: absolute;
  inset: 18px;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border: 1px solid var(--dl-line);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.9);
  color: var(--dl-muted);
}

@media (max-width: 760px) {
  .editor-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .toolbar-left,
  .toolbar-right {
    justify-content: space-between;
    width: 100%;
  }

  .doc-meta strong {
    max-width: 190px;
  }
}
</style>
