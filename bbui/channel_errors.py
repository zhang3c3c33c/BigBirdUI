"""Structured channel facts; business failures are not lost connections."""
import errno


class ChannelFailure(ConnectionError):
    def __init__(self, channel, error):
        super().__init__(str(error) or type(error).__name__)
        self.channel = channel
        self.code = 'control_disconnected' if channel == 'control' else 'observation_failed'


def transport_failed(error):
    if isinstance(error, ChannelFailure):
        return error.channel == 'control'
    if isinstance(error, (ConnectionError, EOFError, TimeoutError)):
        return True
    if isinstance(error, OSError) and error.errno in {
            errno.ECONNRESET, errno.ECONNABORTED, errno.ENOTCONN,
            errno.EPIPE, errno.ETIMEDOUT, 10053, 10054, 10057, 10060}:
        return True
    if (type(error).__module__ == 'adbutils.errors'
            and type(error).__name__ in {'AdbConnectionError', 'AdbTimeout'}):
        return True
    # Optional iOS imports remain lazy; exclude CoreDevice business responses,
    # AFC file errors and all generic OSError/RuntimeError exceptions.
    return (type(error).__module__ == 'pymobiledevice3.exceptions'
            and type(error).__name__ in {'ConnectionTerminatedError', 'StreamClosedError',
                'ChannelClosedError', 'ConnectionFailedError', 'NoDeviceConnectedError'})


def host_error(error, describe=str):
    result = {'error': describe(error)}
    if isinstance(error, ChannelFailure):
        result.update(code=error.code, channel=error.channel)
    return result


def control_failure(phone, error):
    """Record a known failed control write without replaying or hiding receipts."""
    if not isinstance(error, ChannelFailure) or error.channel != 'control':
        return False
    owner = getattr(phone, 'base', phone)
    owner.control_error = str(error) or type(error).__name__
    try:
        owner.stop()
    except OSError:
        pass  # Preserve the original receipt; control_error also blocks input.
    return True


def control_call(function, *args):
    """Use only around a control transport read/write, never around guards."""
    try:
        return function(*args)
    except Exception as error:
        if transport_failed(error):
            raise ChannelFailure('control', error) from error
        raise
