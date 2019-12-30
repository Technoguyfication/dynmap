<?php
// used to make sure connections are authenticated to modify map data

/**
 * Authenticates the current request using the 'key' parameter provided with the request
 * @return boolean True if the user is authorized
 * */
function authenticateUser()
{
	// ensure key file exists
	if (!file_exists('key.php')) {
		http_response_code(500);
		echo ("Access key could not be found on server");
		exit();
	}

	include('key.php');
	return $_REQUEST["key"] = $access_key;
}
