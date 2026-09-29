import re
import json

# 1. Update activity_main.xml
xml_path = 'app/src/main/res/layout/activity_main.xml'
with open(xml_path, 'r', encoding='utf-8') as f:
    xml = f.read()

# Replace the tilThreshold block with High/Low fields
# The original xml has:
# <com.google.android.material.textfield.TextInputLayout android:id="@+id/tilThreshold" ...
# until </com.google.android.material.textfield.TextInputLayout>

new_fields = """
                    <LinearLayout
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:orientation="horizontal">

                        <com.google.android.material.textfield.TextInputLayout
                            android:id="@+id/tilHighThreshold"
                            style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:layout_marginEnd="4dp"
                            android:hint="高于报警"
                            app:boxCornerRadiusBottomEnd="14dp"
                            app:boxCornerRadiusBottomStart="14dp"
                            app:boxCornerRadiusTopEnd="14dp"
                            app:boxCornerRadiusTopStart="14dp"
                            app:boxStrokeColor="@color/gold_primary">

                            <com.google.android.material.textfield.TextInputEditText
                                android:id="@+id/etHighThreshold"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:inputType="numberDecimal"
                                android:singleLine="true"
                                android:textColor="@color/text_primary" />
                        </com.google.android.material.textfield.TextInputLayout>

                        <com.google.android.material.textfield.TextInputLayout
                            android:id="@+id/tilLowThreshold"
                            style="@style/Widget.MaterialComponents.TextInputLayout.OutlinedBox"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:layout_marginStart="4dp"
                            android:hint="低于报警"
                            app:boxCornerRadiusBottomEnd="14dp"
                            app:boxCornerRadiusBottomStart="14dp"
                            app:boxCornerRadiusTopEnd="14dp"
                            app:boxCornerRadiusTopStart="14dp"
                            app:boxStrokeColor="@color/gold_primary">

                            <com.google.android.material.textfield.TextInputEditText
                                android:id="@+id/etLowThreshold"
                                android:layout_width="match_parent"
                                android:layout_height="wrap_content"
                                android:inputType="numberDecimal"
                                android:singleLine="true"
                                android:textColor="@color/text_primary" />
                        </com.google.android.material.textfield.TextInputLayout>
                    </LinearLayout>
"""

# We need to precisely replace the old tilThreshold
xml = re.sub(r'<com\.google\.android\.material\.textfield\.TextInputLayout\s+android:id="@+id/tilThreshold".*?</com\.google\.android\.material\.textfield\.TextInputLayout>', 
             new_fields, xml, flags=re.DOTALL)

with open(xml_path, 'w', encoding='utf-8') as f:
    f.write(xml)

print("Updated activity_main.xml")
