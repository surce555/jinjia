import re

file_path = 'app/src/main/java/com/example/jinjia/MainActivity.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

new_listener = """        binding.btnDashboardAiPrompt.setOnClickListener {
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
    '        binding.btnDashboardAiPrompt.setOnClickListener {\n            copyAiAnalysisPrompt()\n        }',
    new_listener
)

# Wait! The user also has another btnCopyAiPrompt in `cardControl`.
# Let's add the listener for that too.
new_listener_2 = """        binding.btnCopyAiPrompt.setOnClickListener {
            copyAiAnalysisPrompt()
        }"""
        
# Actually, I replaced btnCopyAiPrompt with a LinearLayout containing BOTH btnCopyAiPrompt and btnProfitCalculator in activity_main.xml.
# So I should make sure binding.btnProfitCalculator has the click listener. Wait, is btnProfitCalculator accessible in MainActivity if it was added? Yes, ViewBinding handles it.
# Wait, did btnCopyAiPrompt have a listener already?
# Let's look for `btnCopyAiPrompt.setOnClickListener`.

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Patch applied to MainActivity.kt")
