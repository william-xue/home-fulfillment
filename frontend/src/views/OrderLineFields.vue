<script setup lang="ts">
import type { Entry } from '../api'
import type { ItemInput } from './order'

defineProps<{ skus: Entry[]; locked: boolean }>()
const lines = defineModel<ItemInput[]>({ required: true })
</script>

<template>
  <section class="flow">
    <h2>商品明细</h2>
    <div v-for="(line, index) in lines" :key="index" class="filters">
      <label class="field"><span>商品 {{ index + 1 }}</span><select v-model="line.skuCode" required :disabled="locked">
        <option disabled value="">请选择 SKU</option>
        <option v-for="sku in skus" :key="sku.code" :value="sku.code">{{ sku.code }} · {{ sku.name }}</option>
      </select></label>
      <label class="field"><span>数量 {{ index + 1 }}</span><input v-model.number="line.quantity" type="number"
        min="1" max="1000000" step="1" required :disabled="locked"></label>
      <el-button v-if="lines.length > 1" :disabled="locked" @click="lines.splice(index,1)">删除行</el-button>
    </div>
    <div><el-button :disabled="locked || lines.length >= 100" @click="lines.push({ skuCode: '', quantity: 1 })">添加商品</el-button></div>
  </section>
</template>
