import fs from 'node:fs';
import { DEEPSEEK_MODELS } from '../../node_modules/@earendil-works/pi-ai/dist/providers/deepseek.models.js';
import { OPENAI_MODELS } from '../../node_modules/@earendil-works/pi-ai/dist/providers/openai.models.js';
import { ANTHROPIC_MODELS } from '../../node_modules/@earendil-works/pi-ai/dist/providers/anthropic.models.js';
import { getSupportedThinkingLevels } from '../../node_modules/@earendil-works/pi-ai/dist/models.js';
const rows=[];
for (const [provider,catalog] of Object.entries({deepseek:DEEPSEEK_MODELS,openai:OPENAI_MODELS,anthropic:ANTHROPIC_MODELS})) {
 for(const model of Object.values(catalog)) rows.push({provider,api:model.api,id:model.id,name:model.name,input:model.input,reasoning:model.reasoning,contextWindow:model.contextWindow,maxTokens:model.maxTokens,known:true,source:'pi',thinkingLevels:model.reasoning?getSupportedThinkingLevels(model).map(id=>({id,label:id})):[],defaultThinkingLevel:''});
}
fs.mkdirSync('android/app/src/main/assets',{recursive:true});
fs.writeFileSync('android/app/src/main/assets/model-catalog.json',JSON.stringify(rows));
console.log(`Exported ${rows.length} Pi model metadata records`);
