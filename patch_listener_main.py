import re

file_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

new_listener = """        binding.btnCopyAiPrompt.setOnClickListener {
            copyAiAnalysisPrompt()
        }

        binding.btnProfitCalculator.setOnClickListener {
            val target = selectedTargetItem ?: allTargetsList.firstOrNull()
            if (target != null) {
                com.example.jinjia.ui.ProfitCalculatorDialog(this, target).show()
            } else {
                Toast.makeText(this, "请先选择盯盘标的", Toast.LENGTH_SHORT).show()
            }
        }"""

content = content.replace(
    '        binding.btnCopyAiPrompt.setOnClickListener {\n            copyAiAnalysisPrompt()\n        }',
    new_listener
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied")
