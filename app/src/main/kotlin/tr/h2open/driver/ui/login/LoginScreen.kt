package tr.h2open.driver.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.launch
import tr.h2open.driver.H2oApplication
import tr.h2open.driver.R
import tr.h2open.driver.api.UnauthorizedException
import tr.h2open.driver.ui.common.errorText

class LoginViewModel(private val app: H2oApplication) : ViewModel() {
    var server by mutableStateOf(app.sessions.lastServerUrl)
    var login by mutableStateOf("")
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set

    val canSubmit get() = !busy && server.isNotBlank() && login.isNotBlank() && password.isNotEmpty()

    fun submit() {
        if (!canSubmit) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                app.signIn(server, login, password)
                password = ""
            } catch (e: Exception) {
                error = e
            } finally {
                busy = false
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { LoginViewModel(this[APPLICATION_KEY] as H2oApplication) }
        }
    }
}

@Composable
fun LoginScreen(vm: LoginViewModel = viewModel(factory = LoginViewModel.Factory)) {
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyMedium)

            OutlinedTextField(
                value = vm.server,
                onValueChange = { vm.server = it },
                label = { Text(stringResource(R.string.login_server)) },
                placeholder = { Text("odoo.example.com.tr") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.login,
                onValueChange = { vm.login = it },
                label = { Text(stringResource(R.string.login_user)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.password,
                onValueChange = { vm.password = it },
                label = { Text(stringResource(R.string.login_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.submit() }),
                modifier = Modifier.fillMaxWidth(),
            )

            vm.error?.let {
                val message = if (it is UnauthorizedException) stringResource(R.string.error_wrong_credentials) else errorText(it)
                Text(message, color = MaterialTheme.colorScheme.error)
            }

            Button(onClick = vm::submit, enabled = vm.canSubmit, modifier = Modifier.fillMaxWidth()) {
                if (vm.busy) {
                    CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.login_submit))
                }
            }
        }
    }
}
