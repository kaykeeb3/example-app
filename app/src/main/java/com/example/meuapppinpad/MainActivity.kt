package com.example.meuapppinpad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import br.com.setis.ppconecta.*
import br.com.setis.ppconecta.output.FinishExecCmdOutput
import br.com.setis.ppconecta.output.GetRespOutput
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

class MainActivity : ComponentActivity() {

    // --- MODELO DO PAYLOAD CIELO ---
    data class InitializationPayload(
        val initializationVersion: String,
        val aidParameters: String,   // JSON array em string
        val publicKeys: String       // JSON array em string
    )

    companion object {
        private const val TAG = "PinpadApp"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Configurações da API Cielo
        private const val CIELO_API_URL =
            "https://parametersdownloadsandbox.cieloecommerce.cielo.com.br/api/v0.1/initialization/8df477bb-807b-4348-83ac-604493ad711b/00000001"
        private const val CIELO_TOKEN = ""
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var connectedDevice: BluetoothDevice? = null

    private var lastInitialization: InitializationPayload? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Permissões Bluetooth necessárias", Toast.LENGTH_LONG).show()
        }
    }

    data class DeviceInfo(
        val name: String?,
        val address: String,
        val device: BluetoothDevice
    )

    data class ValidationResult(
        val isValid: Boolean,
        val pinpadVersion: String? = null,
        val cieloVersion: String? = null,
        val message: String? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializar Bluetooth
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            var status by remember { mutableStateOf("Pronto para buscar dispositivos") }
            var isProcessing by remember { mutableStateOf(false) }
            var isConnected by remember { mutableStateOf(false) }
            var devices by remember { mutableStateOf(emptyList<DeviceInfo>()) }
            var showDevices by remember { mutableStateOf(false) }
            var validationResult by remember { mutableStateOf<ValidationResult?>(null) }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Conexão e Validação Pinpad",
                            style = MaterialTheme.typography.h6,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = when {
                                isConnected -> Color(0xFF4CAF50)
                                validationResult?.isValid == false -> Color(0xFFFF5722)
                                else -> MaterialTheme.colors.surface
                            }
                        ) {
                            Text(
                                text = status,
                                modifier = Modifier.padding(16.dp),
                                color = if (isConnected || validationResult != null) Color.White else MaterialTheme.colors.onSurface
                            )
                        }

                        if (isProcessing) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Text(" Processando...", modifier = Modifier.padding(start = 8.dp))
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(
                                onClick = {
                                    isProcessing = true
                                    status = "Buscando dispositivos..."

                                    CoroutineScope(Dispatchers.IO).launch {
                                        if (checkPermissions()) {
                                            val foundDevices = scanDevices()
                                            withContext(Dispatchers.Main) {
                                                devices = foundDevices
                                                showDevices = foundDevices.isNotEmpty()
                                                status = if (foundDevices.isEmpty()) {
                                                    "Nenhum dispositivo encontrado"
                                                } else {
                                                    "${foundDevices.size} dispositivos encontrados"
                                                }
                                                isProcessing = false
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                status = "Permissões negadas"
                                                isProcessing = false
                                            }
                                        }
                                    }
                                },
                                enabled = !isProcessing && !isConnected,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Buscar")
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            if (isConnected) {
                                Button(
                                    onClick = {
                                        disconnect()
                                        isConnected = false
                                        status = "Desconectado"
                                        showDevices = false
                                        validationResult = null
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Desconectar", color = Color.White)
                                }
                            }
                        }

                        if (showDevices && devices.isNotEmpty()) {
                            Text(
                                "Dispositivos pareados:",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )

                            LazyColumn(
                                modifier = Modifier.weight(1f)
                            ) {
                                items(devices) { device ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        elevation = 2.dp
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(
                                                text = device.name ?: "Sem nome",
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = device.address,
                                                style = MaterialTheme.typography.body2,
                                                color = Color.Gray
                                            )

                                            Button(
                                                onClick = {
                                                    isProcessing = true
                                                    status = "Conectando..."

                                                    CoroutineScope(Dispatchers.IO).launch {
                                                        val success = connectToDevice(device.device)
                                                        withContext(Dispatchers.Main) {
                                                            isProcessing = false
                                                            if (success) {
                                                                isConnected = true
                                                                connectedDevice = device.device
                                                                status = "Conectado a ${device.name}"
                                                                showDevices = false
                                                            } else {
                                                                status = "Falha na conexão"
                                                            }
                                                        }
                                                    }
                                                },
                                                enabled = !isProcessing && !isConnected,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 8.dp)
                                            ) {
                                                Text("Conectar")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (isConnected) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                backgroundColor = Color(0xFFF5F5F5)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        "Conexão estabelecida",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1976D2)
                                    )

                                    Button(
                                        onClick = {
                                            isProcessing = true
                                            status = "Enviando comando de teste..."

                                            CoroutineScope(Dispatchers.IO).launch {
                                                val success = sendTestCommand()
                                                withContext(Dispatchers.Main) {
                                                    isProcessing = false
                                                    status = if (success) {
                                                        "Teste de comunicação OK"
                                                    } else {
                                                        "Falha no teste"
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !isProcessing,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp)
                                    ) {
                                        Text("Testar Comunicação")
                                    }

                                    Button(
                                        onClick = {
                                            isProcessing = true
                                            status = "Carregando tabelas EMV..."

                                            CoroutineScope(Dispatchers.IO).launch {
                                                val init = downloadCieloInitialization()
                                                val success = loadEmvTablesToPinpad(init)
                                                withContext(Dispatchers.Main) {
                                                    isProcessing = false
                                                    status = if (success) {
                                                        "Tabelas carregadas"
                                                    } else {
                                                        "Erro na carga de tabelas"
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !isProcessing,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF009688))
                                    ) {
                                        Text("Carregar Tabelas", color = Color.White)
                                    }

                                    Button(
                                        onClick = {
                                            isProcessing = true
                                            validationResult = null
                                            status = "Iniciando validação EMV..."

                                            CoroutineScope(Dispatchers.IO).launch {
                                                val result = performEMVValidation { newStatus ->
                                                    status = newStatus
                                                }
                                                withContext(Dispatchers.Main) {
                                                    validationResult = result
                                                    status = result.message ?: "Validação concluída"
                                                    isProcessing = false
                                                }
                                            }
                                        },
                                        enabled = !isProcessing,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2196F3))
                                    ) {
                                        Text("Validar EMV", color = Color.White)
                                    }
                                }
                            }
                        }

                        validationResult?.let { result ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                backgroundColor = Color(0xFFF0F0F0)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        "Resultado da Validação:",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        if (result.isValid) "Tabelas compatíveis" else "Tabelas incompatíveis",
                                        color = if (result.isValid) Color(0xFF4CAF50) else Color(0xFFFF5722)
                                    )

                                    result.pinpadVersion?.let {
                                        Text("Versão Pinpad: $it", modifier = Modifier.padding(top = 4.dp))
                                    }
                                    result.cieloVersion?.let {
                                        Text("Versão Cielo: $it", modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        return if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
            false
        } else {
            true
        }
    }

    private fun scanDevices(): List<DeviceInfo> {
        val deviceList = mutableListOf<DeviceInfo>()

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return emptyList()
            }

            bluetoothAdapter?.bondedDevices?.forEach { device ->
                deviceList.add(
                    DeviceInfo(
                        name = device.name,
                        address = device.address,
                        device = device
                    )
                )
                Log.d(TAG, "Dispositivo: ${device.name} - ${device.address}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao buscar dispositivos", e)
        }

        return deviceList
    }

    private suspend fun connectToDevice(device: BluetoothDevice): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                disconnect()

                Log.d(TAG, "Conectando ao dispositivo: ${device.address}")

                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    return@withContext false
                }

                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothAdapter?.cancelDiscovery()
                bluetoothSocket?.connect()

                inputStream = bluetoothSocket?.inputStream
                outputStream = bluetoothSocket?.outputStream

                Log.d(TAG, "Conexão estabelecida")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Erro na conexão", e)
                disconnect()
                false
            }
        }
    }

    private fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()

            inputStream = null
            outputStream = null
            bluetoothSocket = null
            connectedDevice = null

            Log.d(TAG, "Desconectado")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar", e)
        }
    }

    private suspend fun sendTestCommand(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                outputStream?.let { output ->
                    val testCommand = "AT\r\n".toByteArray()
                    output.write(testCommand)
                    output.flush()

                    Log.d(TAG, "Comando enviado")
                    delay(500)

                    inputStream?.let { input ->
                        if (input.available() > 0) {
                            val buffer = ByteArray(1024)
                            val bytesRead = input.read(buffer)
                            if (bytesRead > 0) {
                                val response = String(buffer, 0, bytesRead)
                                Log.d(TAG, "Resposta: $response")
                            }
                        }
                    }

                    true
                } ?: false

            } catch (e: Exception) {
                Log.e(TAG, "Erro no teste", e)
                false
            }
        }
    }

    private suspend fun performEMVValidation(
        onStatusUpdate: (String) -> Unit
    ): ValidationResult {
        return try {
            onStatusUpdate("Baixando parâmetros Cielo...")
            val init = downloadCieloInitialization()

            onStatusUpdate("Obtendo versão do Pinpad...")
            val pinpadVersion = getTableVersionFromPinpad()

            onStatusUpdate("Validando versões...")
            val isValid = pinpadVersion == init.initializationVersion.takeLast(10)

            // Se versão inválida, carrega tabela
            if (!isValid) {
                onStatusUpdate("Carregando tabelas EMV...")
                val loaded = loadEmvTablesToPinpad(init)
                if (!loaded) throw Exception("Falha ao carregar tabelas")
            }

            ValidationResult(
                isValid = true,
                pinpadVersion = pinpadVersion,
                cieloVersion = init.initializationVersion,
                message = "Validação e atualização concluídas"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro na validação", e)
            ValidationResult(false, message = "Erro: ${e.message}")
        }
    }


    private suspend fun downloadCieloInitialization(): InitializationPayload {
        return withContext(Dispatchers.IO) {
            val url = URL(CIELO_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $CIELO_TOKEN")

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Erro HTTP: ${connection.responseCode}")
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.readText()
                reader.close()

                val root = JSONObject(response)
                val initVersion = root.optJSONArray("InitializationVersion")?.toString() ?: ""
                val aids = root.optJSONArray("AidParameters")?.toString() ?: ""
                val keys = root.optJSONArray("PublicKeys")?.toString() ?: ""

                val payload = InitializationPayload(initVersion, aids, keys)
                lastInitialization = payload
                Log.d(TAG, "Cielo InitVer=$initVersion")
                payload
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun loadEmvTablesToPinpad(init: InitializationPayload): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val last10 = init.initializationVersion.takeLast(10)

                // Passando parâmetros obrigatórios
                PPConecta.setParam(PPCConst.PPC_INP_INITVER, last10)
                PPConecta.setParam(PPCConst.PPC_INP_TABAID, init.aidParameters)
                PPConecta.setParam(PPCConst.PPC_INP_TABCAPK, init.publicKeys)


                // Executando comando de carga
                val result = PPConecta.startExecCmd(PPCConst.PPC_CMD_TAB_LOAD)
                if (result != IPPConectaError.OK) return@withContext false

                // Aguardar finalização
                var finish: FinishExecCmdOutput
                var timeout = 0
                do {
                    finish = PPConecta.finishExecCmd()
                    if (finish.returnCode == IPPConectaError.PROCESSING) {
                        delay(200)
                        timeout++
                        if (timeout > 150) throw Exception("Timeout TAB_LOAD")
                    }
                } while (finish.returnCode == IPPConectaError.PROCESSING)

                finish.returnCode == IPPConectaError.OK
            } catch (e: Exception) {
                Log.e(TAG, "Erro na carga TAB_LOAD", e)
                false
            }
        }
    }


    private suspend fun getTableVersionFromPinpad(): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Inicializando PPConecta")

                PPConecta.initUSBSerial(this@MainActivity)
                PPConecta.setParam(IPPConectaInput.LICENSE, "84A8152238C0ED1444B9FD05848CA8F9")
                PPConecta.setParam(IPPConectaInput.COMPANY, "Pineappletech")

                val deviceAddress = connectedDevice?.address ?: throw Exception("Dispositivo não conectado")
                val connectResult = IPPConectaInput("BT:$deviceAddress")
                if (connectResult != IPPConectaError.OK) {
                    throw Exception("Erro ao configurar dispositivo: $connectResult")
                }

                val startResult = PPConecta.startExecCmd(IPPConectaCommand.OPEN)
                if (startResult != IPPConectaError.OK) {
                    throw Exception("Erro ao abrir: $startResult")
                }

                var finishOutput: FinishExecCmdOutput
                var timeout = 0
                do {
                    finishOutput = PPConecta.finishExecCmd()
                    if (finishOutput.returnCode == IPPConectaError.PROCESSING) {
                        delay(100)
                        timeout++
                        if (timeout > 300) {
                            throw Exception("Timeout na conexão")
                        }
                    }
                } while (finishOutput.returnCode == IPPConectaError.PROCESSING)

                if (finishOutput.returnCode != IPPConectaError.OK) {
                    throw Exception("Falha na conexão: ${finishOutput.returnCode}")
                }

                val tableVersionResult = PPConecta.startExecCmd(IPPConectaCommand.TABLE_VERSION)
                if (tableVersionResult != IPPConectaError.OK) {
                    throw Exception("Erro ao solicitar versão: $tableVersionResult")
                }

                timeout = 0
                do {
                    finishOutput = PPConecta.finishExecCmd()
                    if (finishOutput.returnCode == IPPConectaError.PROCESSING) {
                        delay(100)
                        timeout++
                        if (timeout > 200) {
                            throw Exception("Timeout na versão")
                        }
                    }
                } while (finishOutput.returnCode == IPPConectaError.PROCESSING)

                if (finishOutput.returnCode != IPPConectaError.OK) {
                    throw Exception("Erro ao obter versão: ${finishOutput.returnCode}")
                }

                val respOutput: GetRespOutput = PPConecta.getResp(0)
                if (respOutput.returnCode != IPPConectaError.OK) {
                    throw Exception("Erro ao ler versão: ${respOutput.returnCode}")
                }

                val version = respOutput.value?.trim() ?: ""
                if (version.isEmpty()) {
                    throw Exception("Versão vazia")
                }

                Log.d(TAG, "Versão Pinpad: $version")
                return@withContext version

            } finally {
                try {
                    PPConecta.abortCmd()
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao fechar PPConecta", e)
                }
            }
        }
    }

    private fun validateVersions(pinpadVersion: String, cieloVersion: String): Boolean {
        Log.d(TAG, "Validando: Pinpad='$pinpadVersion' vs Cielo='$cieloVersion'")

        if (pinpadVersion.equals(cieloVersion, ignoreCase = true)) {
            return true
        }

        val cieloSuffix = cieloVersion.takeLast(10)
        if (pinpadVersion.equals(cieloSuffix, ignoreCase = true)) {
            return true
        }

        val pinpadClean = pinpadVersion.replace(Regex("[^A-Za-z0-9]"), "")
        val cieloClean = cieloVersion.replace(Regex("[^A-Za-z0-9]"), "")
        if (pinpadClean.equals(cieloClean, ignoreCase = true)) {
            return true
        }

        if (cieloVersion.contains(pinpadVersion, ignoreCase = true) ||
            pinpadVersion.contains(cieloVersion, ignoreCase = true)) {
            return true
        }

        return false
    }

    override fun onDestroy() { disconnect(); super.onDestroy() }
}

object IPPConectaInput { const val LICENSE = 1; const val COMPANY = 2 }

object IPPConectaError { const val OK = 0; const val PROCESSING = 1 }

object PPCConst {
    const val PPC_CMD_TAB_LOAD = 32      // corrigido de 50 → 32
    const val PPC_INP_INITVER = 1001     // confirmar IDs na doc
    const val PPC_INP_TABAID  = 1002
    const val PPC_INP_TABCAPK = 1003
}

object IPPConectaCommand {
    const val OPEN = 1
    const val TABLE_VERSION = 31        // corrigido de 15 → 31
}


fun IPPConectaInput(deviceString: String): Int {
    Log.d("PPConecta", "Configurando: $deviceString")
    return IPPConectaError.OK
}
