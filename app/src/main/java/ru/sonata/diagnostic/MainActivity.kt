package ru.sonata.diagnostic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "SONATA",
                            fontSize = 32.sp
                        )
                        Text(
                            "DIAGNOSTIC",
                            fontSize = 24.sp
                        )

                        Spacer(modifier = Modifier.height(32.dp))

                        Text("Hyundai Sonata TagAZ 2.0")

                        Spacer(modifier = Modifier.height(24.dp))

                        Text("OBD2: Не подключен")
                        Text("Двигатель: Ожидание")
                        Text("Запись данных: Выключена")

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(onClick = {}) {
                            Text("Подключить ELM327")
                        }
                    }
                }
            }
        }
    }
}
