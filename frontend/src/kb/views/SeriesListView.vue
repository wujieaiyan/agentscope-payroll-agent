<template>
  <div class="page-shell kb-app">
    <div class="page-head">
      <div>
        <h1 class="page-title">{{ title }}</h1>
        <p class="page-subtitle">{{ subtitle }}</p>
      </div>
      <el-button type="primary" :icon="Plus" size="large" @click="router.push('/kb/create')">
        新建知识库
      </el-button>
    </div>

    <div class="toolbar surface">
      <el-input
        v-model="filters.keyword"
        class="search-input"
        placeholder="搜索名称或简介"
        clearable
        :prefix-icon="Search"
        @keyup.enter="reload"
        @clear="reload"
      />
      <el-select v-model="filters.deptId" placeholder="全部部门" clearable class="filter-select" @change="reload">
        <el-option v-for="dept in departments" :key="dept.id" :label="dept.name" :value="dept.id" />
      </el-select>
      <el-select v-model="filters.status" placeholder="全部状态" clearable class="filter-select" @change="reload">
        <el-option label="正常" value="ACTIVE" />
        <el-option label="草稿" value="DRAFT" />
        <el-option label="已停用" value="DISABLED" />
      </el-select>
      <div class="toolbar-right">
        <span class="count">共 {{ total }} 个知识库</span>
        <el-button :icon="Refresh" circle plain :loading="loading" @click="reload" />
      </div>
    </div>

    <div v-loading="loading" class="list-area">
      <div v-if="seriesList.length" class="card-grid">
        <SeriesCard v-for="item in seriesList" :key="item.id" :series="item" @open="openSeries" />
      </div>

      <el-empty v-else-if="!loading" description="暂无知识库" :image-size="120">
        <el-button type="primary" :icon="Plus" @click="router.push('/kb/create')">
          创建第一个知识库
        </el-button>
      </el-empty>
    </div>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        background
        @current-change="load"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Plus, Search, Refresh } from '@element-plus/icons-vue'
import { orgApi, seriesApi } from '../api'
import SeriesCard from '../components/SeriesCard.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const seriesList = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 12
const departments = ref([])
const filters = reactive({ keyword: '', deptId: null, status: null })

const scope = computed(() => route.query.scope || 'accessible')

const title = computed(() => {
  if (scope.value === 'maintainable') return '我的可维护系列'
  if (scope.value === 'all') return '知识库系统管理'
  return '我的可访问系列'
})

const subtitle = computed(() => {
  if (scope.value === 'maintainable') return '你拥有编辑及以上权限的知识库，可进行文档、目录与配置管理。'
  if (scope.value === 'all') return '浏览全部知识库、运行状态与文档处理情况。'
  return '展示你被授权访问的知识库，支持阅读、检索与按权限维护。'
})

async function loadDepartments() {
  try {
    departments.value = await orgApi.departments()
  } catch (error) {
    departments.value = []
  }
}

async function load() {
  loading.value = true
  try {
    const data = await seriesApi.list({
      scope: scope.value,
      keyword: filters.keyword || undefined,
      deptId: filters.deptId || undefined,
      status: filters.status || undefined,
      page: page.value,
      size: pageSize
    })
    seriesList.value = data.items
    total.value = data.total
  } finally {
    loading.value = false
  }
}

function reload() {
  page.value = 1
  load()
}

function openSeries(id) {
  router.push(`/kb/${id}`)
}

watch(
  () => route.query.scope,
  () => {
    page.value = 1
    load()
  }
)

onMounted(() => {
  loadDepartments()
  load()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  margin-bottom: 18px;
}

.search-input {
  width: 280px;
}

.filter-select {
  width: 150px;
}

.toolbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}

.count {
  font-size: 11px;
  letter-spacing: 0.2px;
  color: var(--kb-muted);
}

.list-area {
  min-height: 280px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(310px, 1fr));
  gap: 16px;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}

@media (max-width: 760px) {
  .toolbar {
    flex-wrap: wrap;
  }

  .search-input,
  .filter-select {
    width: 100%;
  }

  .toolbar-right {
    width: 100%;
    justify-content: flex-end;
  }

  .card-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
