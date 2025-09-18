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
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import java.util.Calendar

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
            ""
        private const val CIELO_TOKEN = ""
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
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

            MaterialTheme(
                colors = lightColors(
                    primary = Color(0xFF1976D2),
                    background = Color.White
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Conexão e Transação Pinpad",
                            style = MaterialTheme.typography.h6,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = if (isConnected) Color(0xFF1976D2) else Color.White
                        ) {
                            Text(
                                text = status,
                                modifier = Modifier.padding(16.dp),
                                color = if (isConnected) Color.White else Color.Black
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

                        // Botões principais
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
                                                status =
                                                    if (foundDevices.isEmpty()) "Nenhum dispositivo encontrado"
                                                    else "${foundDevices.size} dispositivos encontrados"
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
                            ) { Text("Buscar") }

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
                                ) { Text("Desconectar", color = Color.White) }
                            }
                        }

                        // Lista de dispositivos encontrados
                        if (showDevices && devices.isNotEmpty()) {
                            Text("Dispositivos pareados:", fontWeight = FontWeight.Bold)
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(devices) { device ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        elevation = 2.dp
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text(device.name ?: "Sem nome", fontWeight = FontWeight.Medium)
                                            Text(device.address, color = Color.Gray)

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
                                                            } else status = "Falha na conexão"
                                                        }
                                                    }
                                                },
                                                enabled = !isProcessing && !isConnected,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 8.dp)
                                            ) { Text("Conectar") }
                                        }
                                    }
                                }
                            }
                        }

                        // Opções quando conectado
                        if (isConnected) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                backgroundColor = Color(0xFFF5F5F5)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Conexão estabelecida", fontWeight = FontWeight.Bold)

                                    // Botão para validar e carregar tabelas
                                    Button(
                                        onClick = {
                                            isProcessing = true
                                            status = "Iniciando validação EMV..."
                                            CoroutineScope(Dispatchers.IO).launch {
                                                val result = performEMVValidation { newStatus -> status = newStatus }
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
                                            .padding(top = 8.dp)
                                    ) { Text("Validar EMV") }

                                    // Botão para iniciar venda
                                    Button(
                                        onClick = {
                                            isProcessing = true
                                            status = "Iniciando venda..."
                                            CoroutineScope(Dispatchers.IO).launch {
                                                val ok = startTransactionFlow(1000, "01") { s -> status = s }
                                                withContext(Dispatchers.Main) {
                                                    isProcessing = false
                                                    status = if (ok) "Venda concluída" else "Erro na venda"
                                                }
                                            }
                                        },
                                        enabled = !isProcessing,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1976D2))
                                    ) { Text("Iniciar Venda", color = Color.White) }
                                }
                            }
                        }

                        // Resultado da validação
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

    // ------------------- PERMISSÕES E CONEXÃO -------------------

    /**
     * Verifica se todas as permissões Bluetooth necessárias estão concedidas
     * Solicita permissões se necessário
     */
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

    /**
     * Busca por dispositivos Bluetooth pareados
     * Filtra apenas dispositivos que já estão pareados com o Android
     */
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

    /**
     * Conecta ao dispositivo pinpad via PPConecta
     * Configura licença, empresa e endereço MAC do dispositivo
     */
    private suspend fun connectToDevice(device: BluetoothDevice): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                disconnect()

                Log.d(TAG, "Conectando ao dispositivo: ${device.address}")

                connectedDevice = device

                // Configuração para o PPConecta
                PPConecta.setParam(PPCConst.PPC_INP_LICENSE, "")
                PPConecta.setParam(PPCConst.PPC_INP_COMPANY, "")
                PPConecta.setParam(PPCConst.PPC_INP_COMM, "${device.address}")

                val openCheck = execBlocking(PPCConst.PPC_CMD_OPEN, 10000L)
                if (openCheck != IPPConectaError.OK) {
                    Log.e(TAG, "Falha no OPEN antes da venda: $openCheck")
                    return@withContext false
                }

                Log.d(TAG, "Conexão estabelecida")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Erro na conexão", e)
                disconnect()
                false
            }
        }
    }

    /**
     * Desconecta do pinpad e libera recursos
     * Chama ABORT e INIT para limpar estado
     */
    private fun disconnect() {
        try {
            PPConecta.abortCmd()
            PPConecta.execCmdNBlk(PPCConst.PPC_CMD_INIT) // reset
            connectedDevice = null
            Log.d(TAG, "Desconectado via PPConecta")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar", e)
        }
    }

    // ------------------- FUNÇÕES PINPAD -------------------

    /**
     * Helper para executar comandos blocantes corretamente
     * Usa execCmdNBlk() e aguarda o resultado com timeout
     */
    private suspend fun execBlocking(cmd: Int, timeoutMs: Long = 60000L): Int {
        return withContext(Dispatchers.IO) {
            try {
                val result = PPConecta.execCmdNBlk(cmd)
                if (result == IPPConectaError.PROCESSING) {
                    // Aguarda conclusão do comando
                    var elapsed = 0L
                    val interval = 100L

                    while (elapsed < timeoutMs) {
                        delay(interval)
                        elapsed += interval

                        val finishOutput: FinishExecCmdOutput = PPConecta.finishExecCmd()
                        if (finishOutput.returnCode != IPPConectaError.PROCESSING) {
                            return@withContext finishOutput.returnCode
                        }
                    }

                    // Timeout - aborta comando
                    PPConecta.abortCmd()
                    return@withContext -999 // código de timeout
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Erro em execBlocking", e)
                -1
            }
        }
    }

    /**
     * Obtém a versão das tabelas EMV carregadas no pinpad
     * Executa comando TAB_VER e lê a resposta
     */
    private suspend fun getTableVersionFromPinpad(): String {
        return withContext(Dispatchers.IO) {
            try {
                val verResult = execBlocking(PPCConst.PPC_CMD_TAB_VER)
                if (verResult != IPPConectaError.OK) {
                    Log.e(TAG, "Erro ao solicitar versão (TAB_VER): $verResult")
                    return@withContext ""
                }

                val respOutput: GetRespOutput = PPConecta.getResp(PPCConst.PPC_OUT_TABVER)
                if (respOutput.returnCode != IPPConectaError.OK) {
                    Log.e(TAG, "Erro ao ler versão: ${respOutput.returnCode}")
                    return@withContext ""
                }

                val version = respOutput.value?.trim() ?: ""
                Log.d(TAG, "Versão Pinpad: $version")
                version
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao obter versão do Pinpad", e)
                ""
            }
        }
    }

    /**
     * Baixa os parâmetros de inicialização da API da Cielo
     * Retorna InitializationVersion, AidParameters e PublicKeys
     */
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

    /**
     * Carrega tabelas EMV no pinpad somente se necessário (controle por timestamp)
     * Usa comando TAB_LOAD com parâmetros da Cielo
     */
    /**
     * Carrega tabelas EMV no pinpad somente se necessário (controle por timestamp).
     * Segue o fluxo oficial do manual Cielo Conecta v1.11:
     * OPEN → TAB_VER → (TAB_LOAD se necessário) → valida versão.
     */
    private suspend fun loadEmvTablesToPinpad(init: InitializationPayload): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Garante que a sessão está aberta
                val openCheck = execBlocking(PPCConst.PPC_CMD_OPEN, 10000L)
                if (openCheck != IPPConectaError.OK) {
                    Log.e(TAG, "Pinpad não está aberto, OPEN retornou $openCheck")
                    return@withContext false
                }

                // Verifica se precisa carregar baseado no timestamp
                val currentVersion = getTableVersionFromPinpad()
                val cieloVersion = init.initializationVersion.takeLast(10)

                if (currentVersion == cieloVersion && currentVersion.isNotEmpty()) {
                    Log.d(TAG, "Tabelas já atualizadas (versão $currentVersion), pulando carregamento")
                    return@withContext true
                }

                Log.d(TAG, "Carregando tabelas EMV - Atual: $currentVersion, Cielo: $cieloVersion")

                // Valida parâmetros obrigatórios
                if (init.aidParameters.isBlank() || init.aidParameters == "[]") {
                    Log.e(TAG, "Parâmetro AID vazio, não é possível carregar tabelas")
                    return@withContext false
                }
                if (init.publicKeys.isBlank() || init.publicKeys == "[]") {
                    Log.e(TAG, "Parâmetro PublicKeys vazio, não é possível carregar tabelas")
                    return@withContext false
                }

                // Configura parâmetros para carregamento
                PPConecta.setParam(PPCConst.PPC_INP_INITVER, cieloVersion)
                PPConecta.setParam(PPCConst.PPC_INP_TABAID, init.aidParameters)
                PPConecta.setParam(PPCConst.PPC_INP_TABCAPK, init.publicKeys)

                // Executa carga de tabelas (até 2 min)
                val result = execBlocking(PPCConst.PPC_CMD_TAB_LOAD, 120000L)
                if (result != IPPConectaError.OK) {
                    Log.e(TAG, "PPC_CMD_TAB_LOAD falhou: $result")
                    return@withContext false
                }

                // Confirma versão final
                val finalVersion = getTableVersionFromPinpad()
                if (finalVersion.isEmpty()) {
                    Log.e(TAG, "Falha ao obter versão após carregar tabelas")
                    return@withContext false
                }
                if (finalVersion != cieloVersion) {
                    Log.w(TAG, "Versão divergente após carga. Pinpad: $finalVersion, Cielo: $cieloVersion")
                    return@withContext false
                }

                Log.d(TAG, "Tabelas carregadas com sucesso, versão final: $finalVersion")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Erro na carga de tabelas EMV", e)
                false
            }
        }
    }

    /**
     * Realiza validação EMV completa
     * Baixa parâmetros da Cielo, compara versões e carrega tabelas se necessário
     */
    private suspend fun performEMVValidation(
        onStatusUpdate: (String) -> Unit
    ): ValidationResult {
        return try {
            onStatusUpdate("Baixando parâmetros Cielo...")
            val init = downloadCieloInitialization()

            onStatusUpdate("Obtendo versão do Pinpad...")
            val pinpadVersion = getTableVersionFromPinpad()

            onStatusUpdate("Validando versões...")
            val cieloVersion = init.initializationVersion.takeLast(10)
            val isValid = pinpadVersion == cieloVersion

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

    /**
     * Fecha sessão do pinpad corretamente
     * Mostra mensagem de despedida e executa comando CLOSE
     */
    private fun closePinpad() {
        try {
            // Mensagem final no display
            PPConecta.setParam(PPCConst.PPC_INP_DSPMSG, "OBRIGADO\rVOLTE SEMPRE")
            val rc = PPConecta.execCmdNBlk(PPCConst.PPC_CMD_CLOSE)
            if (rc != IPPConectaError.OK) {
                Log.e(TAG, "PPC_CMD_CLOSE retornou $rc")
            } else {
                Log.d(TAG, "PPC_CMD_CLOSE executado")
            }
            connectedDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Erro no closePinpad", e)
        }
    }

    /**
     * Fluxo completo de transação: GETCARD → GETTRACKS/PROCCHIP → FINISHCHIP → CLOSE
     * Implementa o fluxo EMV completo para cartões chip e banda magnética
     */

    /**
     * Fluxo completo de transação com logs detalhados:
     * GETCARD → GETTRACKS/PROCCHIP → FINISHCHIP → REMCARD → CLOSE
     */
    private suspend fun startTransactionFlow(
        amountCents: Long,
        apptype: String,
        onStatusUpdate: (String) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                onStatusUpdate("Aguardando cartão...")
                Log.d(TAG, "===== Iniciando transação =====")
                Log.d(TAG, "Valor: $amountCents centavos | Tipo: $apptype")

                // ---------- Parâmetros obrigatórios GETCARD ----------
                PPConecta.setParam(PPCConst.PPC_INP_TRNTYPE, "00") // Compra
                PPConecta.setParam(PPCConst.PPC_INP_APPTYPE, apptype) // "01"=crédito, "02"=débito
                PPConecta.setParam(PPCConst.PPC_INP_AMOUNT, amountCents.toString()) // Valor em centavos
                PPConecta.setParam(PPCConst.PPC_INP_TIMEOUT, "120") // segundos
                PPConecta.setParam(PPCConst.PPC_INP_DSPMSG, "INSIRA O CARTAO")
                PPConecta.setParam(PPCConst.PPC_INP_CTLSON, "1") // habilita contactless

                val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                PPConecta.setParam(PPCConst.PPC_INP_DATETIME, now)

                val initVersion = lastInitialization?.initializationVersion?.takeLast(10) ?: "0000000000"
                PPConecta.setParam(PPCConst.PPC_INP_INITVER, initVersion)

                // ---------- 1. GETCARD ----------
                val getCardResult = execBlocking(PPCConst.PPC_CMD_GETCARD, 60000L)
                if (getCardResult != IPPConectaError.OK) {

                // ---------- Detalhe do Erro: a execução quebra neste ponto. ----------
                    Log.e(TAG, "GETCARD falhou: $getCardResult")
                    return@withContext false
                }

                val cardType = PPConecta.getResp(PPCConst.PPC_OUT_CARDTYPE).value ?: ""
                Log.d(TAG, "Cartão detectado: $cardType")

                var emvData = ""
                var pan = ""
                var track1 = ""
                var track2 = ""
                var emvResponseData = ""
                var procResult = ""

                when (cardType) {
                    "Emv", "ContactlessEmv" -> {
                        // ---------- 2. PROCCHIP ----------
                        val tagsFirst = ""
                        PPConecta.setParam(PPCConst.PPC_INP_TAGLIST, tagsFirst)

                        val procChipResult = execBlocking(PPCConst.PPC_CMD_PROCCHIP, 60000L)
                        if (procChipResult != IPPConectaError.OK) {
                            Log.e(TAG, "PROCCHIP falhou: $procChipResult")
                            return@withContext false
                        }

                        procResult = PPConecta.getResp(PPCConst.PPC_OUT_RESULT).value ?: ""
                        emvData = PPConecta.getResp(PPCConst.PPC_OUT_EMVDATA).value ?: ""
                        pan = PPConecta.getResp(PPCConst.PPC_OUT_PAN).value ?: ""

                        Log.d(TAG, "PROCCHIP resultado: $procResult | PAN: $pan | EMVData: ${emvData.take(60)}...")

                        when (procResult) {
                            "0" -> {
                                Log.d(TAG, "Aprovada offline pelo cartão")
                                closePinpad()
                                return@withContext true
                            }
                            "1" -> {
                                Log.d(TAG, "Negada offline pelo cartão")
                                closePinpad()
                                return@withContext false
                            }
                            "2" -> {
                                Log.d(TAG, "Requer autorização online")
                            }
                            else -> {
                                Log.e(TAG, "Resultado PROCCHIP inesperado: $procResult")
                                closePinpad()
                                return@withContext false
                            }
                        }
                    }

                    "MagStripe", "ContactlessMagStripe" -> {
                        // ---------- 2. GETTRACKS ----------
                        val getTracksResult = execBlocking(PPCConst.PPC_CMD_GETTRACKS, 30000L)
                        if (getTracksResult != IPPConectaError.OK) {
                            Log.e(TAG, "GETTRACKS falhou: $getTracksResult")
                            return@withContext false
                        }

                        track1 = PPConecta.getResp(PPCConst.PPC_OUT_TRACK1).value ?: ""
                        track2 = PPConecta.getResp(PPCConst.PPC_OUT_TRACK2).value ?: ""

                        Log.d(TAG, "Track1: ${track1.take(40)}...")
                        Log.d(TAG, "Track2: ${track2.take(40)}...")
                    }

                    else -> {
                        Log.e(TAG, "Tipo de cartão não suportado: $cardType")
                        return@withContext false
                    }
                }

                // ---------- 3. Envio ao autorizador ----------
                val authPayload = JSONObject().apply {
                    put("amount", amountCents)
                    put("apptype", apptype)
                    put("cardType", cardType)
                    if (emvData.isNotEmpty()) put("emvData", emvData)
                    if (pan.isNotEmpty()) put("pan", pan)
                    if (track1.isNotEmpty()) put("track1", track1)
                    if (track2.isNotEmpty()) put("track2", track2)
                }
                Log.d(TAG, "Payload para autorizador: $authPayload")

                val returnCode = sendToAcquirerForAuth(authPayload)
                Log.d(TAG, "ReturnCode do autorizador: $returnCode")

                // ---------- 4. FINISHCHIP ----------
                if (cardType == "Emv" || cardType == "ContactlessEmv") {
                    if (procResult == "2") {
                        PPConecta.setParam(PPCConst.PPC_INP_RETCODE, returnCode)
                        if (emvResponseData.isNotEmpty()) {
                            PPConecta.setParam(PPCConst.PPC_INP_EMVDATA, emvResponseData)
                        }
                        val tagsSecond = "917172" // em hexa, exemplo (Issuer scripts)
                        PPConecta.setParam(PPCConst.PPC_INP_TAGLIST, tagsSecond)

                        val finishResult = execBlocking(PPCConst.PPC_CMD_FINISHCHIP, 30000L)
                        if (finishResult != IPPConectaError.OK) {
                            Log.e(TAG, "FINISHCHIP falhou: $finishResult")
                            return@withContext false
                        }
                        Log.d(TAG, "FINISHCHIP concluído com sucesso")
                    } else {
                        Log.d(TAG, "FINISHCHIP não necessário (transação offline)")
                    }
                }

                // ---------- 5. Solicita retirada do cartão ----------
                if (cardType == "Emv" || cardType == "ContactlessEmv") {
                    PPConecta.setParam(PPCConst.PPC_INP_DSPMSG, "RETIRE O CARTAO")
                    execBlocking(PPCConst.PPC_CMD_REMCARD, 30000L)
                    Log.d(TAG, "Solicitada remoção do cartão")
                }

                // ---------- 6. Fecha sessão ----------
                closePinpad()
                Log.d(TAG, "===== Transação finalizada com sucesso =====")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Erro na transação", e)
                try { closePinpad() } catch (_: Exception) { }
                false
            }
        }
    }

    /**
     * Mock do envio ao autorizador - SUBSTITUIR pela chamada real OU backend/Cielo
     * Simula aprovação automática para testes
     */
    private fun sendToAcquirerForAuth(payload: JSONObject): String {
        Log.d(TAG, "Simulando envio ao autorizador: $payload")

        // Cria um JSON de exemplo programaticamente
        val examplePayload = JSONObject().apply {
            put("MerchantOrderId", "123456789123456")

            put("Customer", JSONObject().apply {
                put("Name", "Comprador crédito completo")
                put("Identity", "11225468954")
                put("IdentityType", "CPF")
                put("Email", "compradorteste@teste.com")
                put("Birthday", "1991-01-02")
                put("Address", JSONObject().apply {
                    put("Street", "Rua Teste")
                    put("Number", "123")
                    put("Complement", "AP 123")
                    put("ZipCode", "12345987")
                    put("City", "São Paulo")
                    put("State", "SP")
                    put("Country", "BR")
                })
                put("DeliveryAddress", JSONObject().apply {
                    put("Street", "Rua Teste")
                    put("Number", "123")
                    put("Complement", "AP 123")
                    put("ZipCode", "12345987")
                    put("City", "São Paulo")
                    put("State", "SP")
                    put("Country", "BR")
                })
            })

            put("Payment", JSONObject().apply {
                put("SubordinatedMerchantId", "")
                put("Type", "PhysicalCreditCard")
                put("SoftDescriptor", "Teste API")
                put("PaymentDateTime", "2025-09-10T13:30:48")
                put("Amount", 400)
                put("Installments", 1)
                put("Interest", "ByMerchant")
                put("Capture", true)
                put("ProductId", 1)

                put("CreditCard", JSONObject().apply {
                    put("CardNumber", "EC9A221AC2E165A7")
                    put("BrandId", 1)
                    put("IssuerId", 1001)
                    put("ExpirationDate", "12/2030")
                    put("SecurityCodeStatus", "Collected")
                    put("SecurityCode", "123")
                    put("EncryptedCardData", JSONObject().apply {
                        put("EncryptionType", "Dukpt3DesCBC")
                        put("InitializationVector", "0000000000000000")
                        put("CardNumberKSN", "FFFFF99995C1B4400004")
                    })
                    put("InputMode", "Typed")
                    put("AuthenticationMethod", "NoPassword")
                    put("TruncateCardNumberWhenPrinting", true)
                    put("SaveCard", true)
                })

                put("PinPadInformation", JSONObject().apply {
                    put("PhysicalCharacteristics", "PinPadWithChipReaderWithoutSamAndContactless")
                    put("ReturnDataInfo", "00")
                    put("SerialNumber", "0820471929")
                    put("TerminalId", "00000001")
                })
            })
        }

        Log.d(TAG, "Payload de teste criado: $examplePayload")

        return "00" // Sempre aprovado para testes
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}

// ------------------- CONSTANTES -------------------

object IPPConectaError {
    const val OK = 0
    const val PROCESSING = 1
}

object PPCConst {
    // Comandos básicos
    const val PPC_CMD_INIT = 0
    const val PPC_CMD_OPEN = 1
    const val PPC_CMD_CLOSE = 2

    // Comandos de display e entrada
    const val PPC_CMD_REMCARD = 16

    // Comandos de tabelas EMV
    const val PPC_CMD_TAB_VER = 31
    const val PPC_CMD_TAB_LOAD = 32

    // Comandos de processamento de cartão
    const val PPC_CMD_GETCARD = 41
    const val PPC_CMD_GETTRACKS = 42
    const val PPC_CMD_PROCCHIP = 43
    const val PPC_CMD_FINISHCHIP = 44


    // Parâmetros de entrada (PPC_INP)
    const val PPC_INP_COMM = 1
    const val PPC_INP_DSPMSG = 2
    const val PPC_INP_INITVER = 9
    const val PPC_INP_TABAID = 10
    const val PPC_INP_TABCAPK = 11
    const val PPC_INP_DATETIME = 15

    const val PPC_INP_TRNTYPE = 16

    const val PPC_INP_TAGLIST = 22
    const val PPC_INP_RETCODE = 24

    const val PPC_INP_EMVDATA = 25

    const val PPC_INP_CTLSON = 27
    const val PPC_INP_LICENSE = 47
    const val PPC_INP_COMPANY = 48
    const val PPC_INP_AMOUNT = 50
    const val PPC_INP_APPTYPE = 51
    const val PPC_INP_TIMEOUT = 52

    const val PPC_OUT_RESULT = 134

    // Parâmetros de saída (PPC_OUT)
    const val PPC_OUT_TABVER = 117
    const val PPC_OUT_CARDTYPE = 121    // tipo do cartão
    const val PPC_OUT_TRACK1 = 129
    const val PPC_OUT_TRACK2 = 130
    const val PPC_OUT_EMVDATA = 135
    const val PPC_OUT_PAN = 138
}
