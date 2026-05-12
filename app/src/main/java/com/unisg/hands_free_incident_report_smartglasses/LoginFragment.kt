package com.unisg.hands_free_incident_report_smartglasses

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.auth0.android.Auth0
import com.auth0.android.authentication.AuthenticationAPIClient
import com.auth0.android.authentication.AuthenticationException
import com.auth0.android.authentication.storage.CredentialsManager
import com.auth0.android.authentication.storage.SharedPreferencesStorage
import com.auth0.android.callback.Callback
import com.auth0.android.provider.WebAuthProvider
import com.auth0.android.result.Credentials
import com.unisg.hands_free_incident_report_smartglasses.R
import com.unisg.hands_free_incident_report_smartglasses.databinding.FragmentLoginBinding

class LoginFragment : Fragment() {

    private lateinit var account: Auth0
    private lateinit var credentialsManager: CredentialsManager

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        account = Auth0.getInstance(
            getString(R.string.com_auth0_client_id),
            getString(R.string.com_auth0_domain)
        )
        val authClient = AuthenticationAPIClient(account)
        credentialsManager = CredentialsManager(authClient, SharedPreferencesStorage(requireContext()))

        // Already logged in → skip login screen
        if (credentialsManager.hasValidCredentials()) {
            navigateToHome()
            return
        }

        binding.btnLogin.setOnClickListener { login() }
    }

    private fun login() {
        WebAuthProvider
            .login(account)
            .withScheme(getString(R.string.com_auth0_scheme))
            .withAudience(getString(R.string.com_auth0_audience))
            .start(requireActivity(), object : Callback<Credentials, AuthenticationException> {
                override fun onSuccess(result: Credentials) {
                    credentialsManager.saveCredentials(result)
                    navigateToHome()
                }
                override fun onFailure(error: AuthenticationException) {
                    Toast.makeText(requireContext(), "Login failed", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun navigateToHome() {
        findNavController().navigate(R.id.action_login_to_home)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
