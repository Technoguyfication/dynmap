<?php
// Used for interacting with markers

include('authentication.php');

switch (strtolower($_REQUEST["action"] ?? null)) {
	case "setfile":
		setMarkerFile();
		break;
	case "deletefile":
		deleteMarkerFile();
		break;
	case "setimage":
		setMarkerImageFile();
		break;
	case "deleteimage":
		deleteMarkerImageFile();
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
 * Sets the marker file for a world
 * */
function setMarkerFile()
{
	checkAuthentication();
	checkMethod("POST");

	$marker = [
		"world" => $_REQUEST["world"] ?? null,
		"content" => $_REQUEST["content"] ?? null
	];

	// make sure no parameters were left null
	foreach ($marker as $k => $v) {
		if (!isset($v)) {
			response("Missing parameter: $k", 400);	// bad request
			return;
		}
	}

	// ensure markers directory exists
	$marker_file_path = getMarkerFile($marker["world"]);
	if (!file_exists(dirname($marker_file_path))) {
		mkdir(dirname($marker_file_path), 0777, true);
	}

	// set marker file
	try {
		$marker_file = fopen($marker_file_path, "w");
		if (!$marker_file || !fwrite($marker_file, $marker["content"]) || !fclose($marker_file)) {
			// failed to write marker file
			throw new Exception("Failed to write marker file");
		}
	} catch (Exception $ex) {
		response($ex->getMessage(), 500);
		return;
	}

	response(null, 200);	// success
}

/**
 * Deletes a marker file
 * */
function deleteMarkerFile()
{
	checkAuthentication();
	checkMethod("POST");

	$marker = [
		"world" => $_REQUEST["world"] ?? null
	];

	// make sure no parameters were left null
	foreach ($marker as $k => $v) {
		if (!isset($v)) {
			response("Missing parameter: $k", 400);	// bad request
			return;
		}
	}

	// delete the file
	$marker_file_path = getMarkerFile($marker["world"]);
	if (!unlink($marker_file_path)) {
		response("Failed to delete file " . $marker_file_path, 500);
	}
}

/**
 * Deletes a marker image file
 * */
function deleteMarkerImageFile()
{
	checkAuthentication();
	checkMethod("POST");

	$image = [
		"markerid" => $_REQUEST["markerid"] ?? null
	];

	// make sure no parameters were left null
	foreach ($image as $k => $v) {
		if (!isset($v)) {
			response("Missing parameter: $k", 400);	// bad request
			return;
		}
	}

	// delete the file
	$marker_file_path = getMarkerImageFile($image["markerid"]);
	if (!unlink($marker_file_path)) {
		response("Failed to delete file " . $marker_file_path, 500);
	}
}

/**
 * Creates a marker image file
 * */
function setMarkerImageFile()
{
	checkAuthentication();
	checkMethod("POST");

	$image = [
		"markerid" => $_REQUEST["markerid"] ?? null
	];

	$file = $_FILES["file"] ?? null;	// get reference to uploaded file

	// make sure no parameters were left null
	foreach ($image as $k => $v) {
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
	$image_file_path = getMarkerImageFile($image["markerid"]);
	//$image_file_container = dirname($image_file_path);

	// create tile container if it doesn't exist
	if (!file_exists(dirname($image_file_path))) {
		mkdir(dirname($image_file_path), 0777, true);
	}

	// move uploaded image file to location
	try {
		if (!move_uploaded_file($file["tmp_name"], $image_file_path)) {
			throw new Exception("Failed to write image file");
		}
	} catch (Exception $ex) {
		response($ex->getMessage(), 500);
		return;
	}

	response(null, 200);	// success
}

function getMarkerFile($world)
{
	return sprintf("%s/markers/%s.json", dirname(__FILE__), $world);
}

function getMarkerImageFile($image_name)
{
	return sprintf("%s/markers/images/%s.png", dirname(__FILE__), $image_name);
}

function response($body, $code = 200)
{
	http_response_code($code);
	echo ($body . "\n");
}
