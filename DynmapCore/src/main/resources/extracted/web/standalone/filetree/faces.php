<?php
// Used for interacting with player faces

include('authentication.php');

switch (strtolower($_REQUEST["action"] ?? null)) {
	case "setfile":
		setFile();
		break;
	case "deletefile":
		deleteFile();
		break;
	default:
		response("Invalid or missing 'action'", 400);
		break; // bad request
}

/**
 * Enforces that the request has a valid key sent with it
 * */
function checkAuthentication()
{
	if (!authenticateUser()) {
		response(null, 401);	// unauthorized
		exit();
	}
}

/**
 * Enforces that the specified method be used for the request, exits the script if otherwise
 * @param string $method The method to be used
 * */
function checkMethod(string $method)
{
	if ($_SERVER["REQUEST_METHOD"] != $method) {
		response(null, 405);	// method not allowed
		exit();
	}
}

/**
 * Creates a face image file
 * */
function setFile()
{
	checkAuthentication();
	checkMethod("POST");

	$face = [
		"player_name" => $_REQUEST["player_name"] ?? null,
		"type" => $_REQUEST["type"] ?? null
	];

	$file = $_FILES["file"] ?? null;	// get reference to uploaded file

	// make sure no parameters were left null
	foreach ($face as $k => $v) {
		if (!isset($v)) {
			response("Missing parameter: $k", 400);	// bad request
			return;
		}
	}

	// ensure file is uploaded
	if (!is_array($file)) {
		response("File upload missing", 400);
		return;
	}

	// create paths for the newly created file
	$face_file_path = getFaceFile($face);

	// create tile container if it doesn't exist
	if (!file_exists(dirname($face_file_path))) {
		mkdir(dirname($face_file_path), 0777, true);
	}

	// move uploaded image file to location
	try {
		if (!move_uploaded_file($file["tmp_name"], $face_file_path)) {
			throw new Exception("Failed to write image file");
		}
	} catch (Exception $ex) {
		response($ex->getMessage(), 500);
		return;
	}

	response(null, 200);	// success
}


/**
 * Deletes a face image file
 * */
function deleteFile()
{
	checkAuthentication();
	checkMethod("POST");

	$face = [
		"player_name" => $_REQUEST["player_name"] ?? null,
		"type" => $_REQUEST["type"] ?? null
	];

	// make sure no parameters were left null
	foreach ($face as $k => $v) {
		if (!isset($v)) {
			response("Missing parameter: $k", 400);	// bad request
			return;
		}
	}

	// delete the file
	$face_file_path = getMarkerImageFile($face);
	if (!unlink($face_file_path)) {
		response("Failed to delete file " . $face_file_path, 500);
	}
}

function getFaceFile($face)
{
	// /filetree/faces/16x16/Notch.png
	return sprintf("%s/faces/%s/%s.png", dirname(__FILE__), $face["type"], $face["player_name"]);
}

function response($body, $code = 200)
{
	http_response_code($code);
	echo ($body . "\n");
}
